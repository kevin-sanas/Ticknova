package com.ticknova.ai;

import com.ticknova.ai.model.*;
import com.ticknova.shared.Actor;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AiRetriever {
    public enum Strategy { LEXICAL, VECTOR, HYBRID }
    public record Candidate(AiDtos.Citation citation,String content,double score) {}
    private final JdbcClient jdbc;
    private final AiModel model;
    private final AiAuthorization authorization;
    public AiRetriever(JdbcClient jdbc,AiModel model,AiAuthorization authorization) {this.jdbc=jdbc;this.model=model;this.authorization=authorization;}
    /** ACL and current publication are materialized before either candidate list. No global recall. */
    public List<Candidate> retrieve(Actor actor,List<UUID> spaces,String question) {
        return retrieve(actor,spaces,question,Strategy.HYBRID);
    }
    /** Evaluation seam: all strategies share the production authorization and evidence gates. */
    public List<Candidate> retrieve(Actor actor,List<UUID> spaces,String question,Strategy strategy) {
        Objects.requireNonNull(strategy);
        authorization.scopes(actor,spaces);var terms=AiText.terms(question==null?"":question);
        if(spaces.isEmpty()||terms.isEmpty())return List.of();
        float[] unused=new float[1024];unused[0]=1;
        var vectors=strategy==Strategy.LEXICAL?List.of(unused):model.embed(List.of(question));
        if(vectors==null||vectors.size()!=1)throw new AiModelFailure("AI_OUTPUT_INVALID",false);
        String vector=AiText.vectorLiteral(vectors.getFirst());
        // Uncalibrated conservative evidence gate: >=30% lexical query coverage and >=2 matches
        // (one for a one-term query). Only real providers may use cosine similarity >=0.75
        // independently; deterministic mock hash collisions are not semantic evidence.
        // AiText emits only Han/Latin/digits; quote every term and bind the complete tsquery.
        String query=terms.stream().map(t->"'"+t.replace("'","''")+"'").reduce((a,b)->a+" | "+b).orElse("");
        return jdbc.sql("""
            WITH authorized AS MATERIALIZED (
              SELECT c.id,c.content,c.page_no,c.embedding,c.lexical_tsv,d.id document_id,v.id version_id,v.version_no,d.title,
                (SELECT count(*) FROM unnest(CAST(:terms AS text[])) t(term) WHERE c.lexical_tsv @@ plainto_tsquery('simple',t.term)) overlap_count
              FROM knowledge_chunk c JOIN document_version v ON v.id=c.document_version_id
              JOIN knowledge_document d ON d.id=v.document_id JOIN knowledge_space s ON s.id=d.space_id
              JOIN knowledge_space_member m ON m.space_id=s.id AND m.user_id=:actor
              JOIN app_user u ON u.id=m.user_id AND u.active AND NOT u.must_change_password
              WHERE s.active AND m.can_read AND s.id IN (:spaces) AND d.publication_state='PUBLISHED'
              AND d.published_version_id=v.id AND v.publication_state='PUBLISHED' AND v.ingest_state='READY' AND v.review_state='APPROVED'
              AND v.embedding_state='READY' AND v.embedding_profile=:profile AND c.embedding_profile=:profile AND c.embedding IS NOT NULL
            ), vector_candidates AS (
              SELECT id,row_number() OVER(ORDER BY embedding <=> CAST(:vector AS vector),id) r
              FROM authorized WHERE :useVector ORDER BY embedding <=> CAST(:vector AS vector),id LIMIT 8
            ), lexical_candidates AS (
              SELECT id,row_number() OVER(ORDER BY ts_rank_cd(lexical_tsv,to_tsquery('simple',:query)) DESC,id) r
              FROM authorized WHERE :useLexical AND lexical_tsv @@ to_tsquery('simple',:query)
              ORDER BY ts_rank_cd(lexical_tsv,to_tsquery('simple',:query)) DESC,id LIMIT 8
            ), fused AS (
              SELECT id,sum(score) score FROM (SELECT id,1.0/(60+r) score FROM vector_candidates UNION ALL SELECT id,1.0/(60+r) score FROM lexical_candidates) f GROUP BY id
            )
            SELECT a.*,f.score FROM authorized a JOIN fused f ON f.id=a.id
            WHERE (a.overlap_count>=:minimum AND a.overlap_count>=:coverage)
               OR (:semantic AND (a.embedding <=> CAST(:vector AS vector))<=0.25)
            ORDER BY f.score DESC,a.id LIMIT 6
            """).param("actor",actor.id()).param("spaces",spaces).param("profile",model.profile()).param("vector",vector).param("query",query).param("terms","{"+String.join(",",terms)+"}")
            .param("useVector",strategy!=Strategy.LEXICAL).param("useLexical",strategy!=Strategy.VECTOR)
            .param("minimum",Math.min(2,terms.size())).param("coverage",Math.ceil(terms.size()*0.30)).param("semantic",strategy!=Strategy.LEXICAL&&!"mock".equals(model.mode()))
            .query((r,n)->new Candidate(new AiDtos.Citation(r.getObject("document_id",UUID.class),r.getObject("version_id",UUID.class),r.getInt("version_no"),r.getString("title"),(Integer)r.getObject("page_no"),r.getObject("id",UUID.class),snippet(r.getString("content"))),r.getString("content"),r.getDouble("score"))).list();
    }
    private static String snippet(String s){return s.length()<=400?s:s.substring(0,400);}
}

