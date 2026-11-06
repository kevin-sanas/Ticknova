-- Notify only after the surrounding transaction commits. Payloads contain no
-- row identifiers or business data; consumers re-read the authorized snapshot.
CREATE FUNCTION analytics_change_notify() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_notify('ticknova_analytics', '');
    RETURN NULL;
END;
$$;

CREATE TRIGGER analytics_ticket_changed AFTER INSERT OR UPDATE OR DELETE ON ticket
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();
CREATE TRIGGER analytics_transition_changed AFTER INSERT OR UPDATE OR DELETE ON ticket_transition
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();
CREATE TRIGGER analytics_space_changed AFTER INSERT OR UPDATE OR DELETE ON knowledge_space
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();
CREATE TRIGGER analytics_document_changed AFTER INSERT OR UPDATE OR DELETE ON knowledge_document
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();
CREATE TRIGGER analytics_version_changed AFTER INSERT OR UPDATE OR DELETE ON document_version
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();
CREATE TRIGGER analytics_suggestion_changed AFTER INSERT OR UPDATE OR DELETE ON ai_suggestion
    FOR EACH STATEMENT EXECUTE FUNCTION analytics_change_notify();

CREATE FUNCTION analytics_feedback_notify() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.action = 'AI_FEEDBACK_RECORDED' THEN
            PERFORM pg_notify('ticknova_analytics', '');
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.action = 'AI_FEEDBACK_RECORDED' THEN
            PERFORM pg_notify('ticknova_analytics', '');
        END IF;
    ELSIF OLD.action = 'AI_FEEDBACK_RECORDED' OR NEW.action = 'AI_FEEDBACK_RECORDED' THEN
        PERFORM pg_notify('ticknova_analytics', '');
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER analytics_feedback_changed AFTER INSERT OR UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION analytics_feedback_notify();
