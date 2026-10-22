package com.ticknova.knowledge;

import com.ticknova.shared.ApiException;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadStorage {
    private final Path root;
    private final long maximum;
    public UploadStorage(@Value("${ticknova.knowledge.upload-root:data/uploads}") String root,@Value("${ticknova.knowledge.max-upload-bytes:20971520}") long maximum) {
        this.root=Path.of(root).toAbsolutePath().normalize(); this.maximum=maximum;
        if(maximum<1||maximum>20L*1024*1024) throw new IllegalArgumentException("Invalid upload bound");
    }
    public record Validated(String name,String mime,String sha256,byte[] bytes) {}
    public Validated validateMarkdown(String markdown) {
        byte[] bytes=markdown.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0)throw new ApiException(400,"EMPTY_FILE","文件内容为空");
        if(bytes.length>maximum)throw tooLarge();
        try{return new Validated("article.md","text/markdown",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes);}
        catch(NoSuchAlgorithmException e){throw new ApiException(503,"UPLOAD_STORAGE_UNAVAILABLE","文件存储暂时不可用");}
    }
    public Validated validate(MultipartFile file) {
        String name=file.getOriginalFilename();
        if(name==null||name.isBlank()||name.length()>255||name.contains("/")||name.contains("\\")||name.contains(":")||name.chars().anyMatch(c->c<32||c==127)) throw new ApiException(400,"INVALID_FILE_NAME","文件名无效");
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String mime=switch(ext) {case "pdf"->"application/pdf";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "md"->"text/markdown";case "txt"->"text/plain";default->throw unsupported();};
        String declared=Objects.requireNonNullElse(file.getContentType(),"application/octet-stream").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(!declared.equals(mime)&&!declared.equals("application/octet-stream")&&!(mime.equals("text/markdown")&&declared.equals("text/plain"))) throw unsupported();
        if(file.isEmpty()) throw new ApiException(400,"EMPTY_FILE","文件内容为空");
        if(file.getSize()>maximum) throw tooLarge();
        try(InputStream in=file.getInputStream()) {
            byte[] bytes=in.readNBytes((int)maximum+1); if(bytes.length>maximum) throw tooLarge();
            if(mime.equals("application/pdf")) { if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw unsupported(); }
            else if(ext.equals("docx")) validateDocx(bytes);
            else { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); if(new String(bytes,StandardCharsets.UTF_8).indexOf('\0')>=0)throw unsupported(); }
            return new Validated(name,mime,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),bytes);
        } catch(CharacterCodingException e) { throw new ApiException(415,"INVALID_TEXT_ENCODING","文本文件必须为 UTF-8 编码"); }
        catch(IOException|NoSuchAlgorithmException e) { throw new ApiException(503,"UPLOAD_STORAGE_UNAVAILABLE","文件存储暂时不可用"); }
    }
    private static void validateDocx(byte[] bytes) throws IOException {
        boolean content=false,document=false; int entries=0; long expanded=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>10000)throw unsupported();
                content|=entry.getName().equals("[Content_Types].xml"); document|=entry.getName().equals("word/document.xml");
                int n; while((n=zip.read(buffer))!=-1) { expanded+=n; if(expanded>64L*1024*1024)throw new ApiException(413,"DOCUMENT_EXPANDED_TOO_LARGE","文档解压内容超过限制"); }
            }
        } catch(ZipException e) {throw unsupported();}
        if(!content||!document)throw unsupported();
    }
    public String save(Validated file) {
        String key=UUID.randomUUID()+".bin"; Path target=path(key); Path temp=null;
        try {
            Files.createDirectories(root); if(Files.isSymbolicLink(root))throw new IOException("Unsafe root");
            temp=Files.createTempFile(root,"upload-",".tmp"); Files.write(temp,file.bytes()); Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) remove(key); }
            });
            return key;
        } catch(IOException e) {if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){} throw new ApiException(503,"UPLOAD_STORAGE_UNAVAILABLE","文件存储暂时不可用");}
    }
    public Path path(String key) { if(key==null||!key.matches("[0-9a-f-]{36}\\.bin"))throw new IllegalArgumentException("Invalid storage key"); Path p=root.resolve(key).normalize(); if(!p.getParent().equals(root))throw new IllegalArgumentException("Unsafe storage key"); return p; }
    public void remove(String key) { try {Files.deleteIfExists(path(key));}catch(IOException ignored) { /* Orphans are retained for audited maintenance. */ } }
    private static ApiException tooLarge(){return new ApiException(413,"UPLOAD_TOO_LARGE","文件超过允许大小");}
    private static ApiException unsupported(){return new ApiException(415,"UNSUPPORTED_FILE_TYPE","文件格式、内容或 MIME 类型不匹配");}
}
