package cn.trial.videosearch.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.*;

@Service
public class UserService {
    public record Create(String username,String displayName,String passwordHash,Boolean admin) {}
    public record Update(String displayName,Boolean enabled,Boolean admin,long expectedVersion) {}
    public record Reset(String passwordHash,long expectedVersion) {}
    private final JdbcTemplate db;
    public UserService(JdbcTemplate db) { this.db=db; }
    public void requireAdmin(String username) {
        if(username==null || !Long.valueOf(1).equals(db.queryForObject("SELECT count(*) FROM user_accounts WHERE username=? AND enabled AND is_admin AND NOT deleted",Long.class,username)))
            throw new AdminService.Rejected(403,"admin_required");
    }
    static String username(String value) {
        if(value==null) throw new IllegalArgumentException("invalid username");
        String name=value.strip().toLowerCase(Locale.ROOT);
        if(!name.matches("[a-z0-9_.@-]{3,80}")) throw new IllegalArgumentException("invalid username");return name;
    }
    static void hash(String hash) {
        if(hash==null || !hash.matches("scrypt\\$32768\\$8\\$1\\$[A-Za-z0-9_-]{22}\\$[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("invalid credential encoding");
    }
    static String display(String value) {
        if(value==null || value.isBlank() || value.length()>100 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid display name");return value.strip();
    }
    private Map<String,Object> publicView(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("username","display_name","enabled","is_admin","row_version","created_at","updated_at")) {
            var value=row.get(field);result.put(field.equals("row_version")?"version":field,value instanceof Timestamp time?time.toInstant().toString():value);
        }
        return result;
    }
    public Map<String,Object> list(int limit,String after) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("invalid user page");
        if(after!=null) after=username(after);
        var rows=db.queryForList("SELECT username,display_name,enabled,is_admin,row_version,created_at,updated_at FROM user_accounts WHERE NOT deleted AND (?::text IS NULL OR username>?) ORDER BY username LIMIT ?",after,after,limit+1);
        boolean more=rows.size()>limit;var items=rows.subList(0,Math.min(limit,rows.size())).stream().map(this::publicView).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("has_more",more);result.put("next_cursor",more?items.getLast().get("username"):null);return result;
    }
    private Map<String,Object> locked(String username,long version) {
        var rows=db.queryForList("SELECT * FROM user_accounts WHERE username=? AND NOT deleted FOR UPDATE",username);
        if(rows.size()!=1) throw new AdminService.Rejected(404,"user_unavailable");
        var row=rows.getFirst();if(((Number)row.get("row_version")).longValue()!=version) throw new AdminService.Rejected(409,"user_revision_conflict");return row;
    }
    private void lock() { db.execute("SELECT pg_advisory_xact_lock(1122334455)"); }
    private void audit(String actor,String action,String target) { db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,?,?,?)",actor,action,target,UUID.randomUUID()); }
    @Transactional
    public Map<String,Object> create(Create body,String actor) {
        if(body==null || body.admin()==null) throw new IllegalArgumentException("invalid account");
        String name=username(body.username()),label=display(body.displayName());hash(body.passwordHash());lock();requireAdmin(actor);
        if(db.queryForObject("SELECT count(*) FROM user_accounts WHERE username=?",Long.class,name)>0) throw new AdminService.Rejected(409,"username_exists");
        db.update("INSERT INTO user_accounts(username,display_name,password_hash,is_admin) VALUES(?,?,?,?)",name,label,body.passwordHash(),body.admin());
        audit(actor,"user.create",name);return Map.of("user",publicView(locked(name,1)));
    }
    @Transactional
    public Map<String,Object> update(String raw,Update body,String actor) {
        if(body==null || body.enabled()==null || body.admin()==null || body.expectedVersion()<1) throw new IllegalArgumentException("invalid update");
        String name=username(raw),label=display(body.displayName());lock();requireAdmin(actor);var previous=locked(name,body.expectedVersion());
        if((name.equals(actor) || name.equals("admin")) && (!body.enabled() || !body.admin())) throw new AdminService.Rejected(409,"protected_account");
        if(Boolean.TRUE.equals(previous.get("enabled")) && Boolean.TRUE.equals(previous.get("is_admin")) && (!body.enabled() || !body.admin())
                && db.queryForObject("SELECT count(*) FROM user_accounts WHERE enabled AND is_admin AND NOT deleted",Long.class)<=1) throw new AdminService.Rejected(409,"last_admin_required");
        boolean revoke=!Objects.equals(previous.get("enabled"),body.enabled()) || !Objects.equals(previous.get("is_admin"),body.admin());
        db.update("UPDATE user_accounts SET display_name=?,enabled=?,is_admin=?,row_version=row_version+1,credential_revision=credential_revision+?,updated_at=clock_timestamp() WHERE username=?",label,body.enabled(),body.admin(),revoke?1:0,name);
        audit(actor,"user.update",name);return Map.of("user",publicView(locked(name,body.expectedVersion()+1)));
    }
    @Transactional
    public Map<String,Object> reset(String raw,Reset body,String actor) {
        if(body==null || body.expectedVersion()<1) throw new IllegalArgumentException("invalid reset");
        hash(body.passwordHash());String name=username(raw);lock();requireAdmin(actor);locked(name,body.expectedVersion());
        db.update("UPDATE user_accounts SET password_hash=?,credential_revision=credential_revision+1,row_version=row_version+1,updated_at=clock_timestamp() WHERE username=?",body.passwordHash(),name);
        audit(actor,"user.password_reset",name);return Map.of("user",publicView(locked(name,body.expectedVersion()+1)));
    }
    @Transactional
    public Map<String,Object> remove(String raw,long expectedVersion,String actor) {
        String name=username(raw);if(name.equals(actor) || name.equals("admin")) throw new AdminService.Rejected(409,"protected_account");
        lock();requireAdmin(actor);var previous=locked(name,expectedVersion);
        if(Boolean.TRUE.equals(previous.get("enabled")) && Boolean.TRUE.equals(previous.get("is_admin"))
                && db.queryForObject("SELECT count(*) FROM user_accounts WHERE enabled AND is_admin AND NOT deleted",Long.class)<=1) throw new AdminService.Rejected(409,"last_admin_required");
        db.update("UPDATE user_accounts SET deleted=true,enabled=false,credential_revision=credential_revision+1,row_version=row_version+1,updated_at=clock_timestamp() WHERE username=?",name);
        audit(actor,"user.delete",name);return Map.of("removed",true);
    }
    public Optional<Map<String,Object>> credential(String raw) {
        String name;try {name=username(raw);} catch(IllegalArgumentException invalid) {return Optional.empty();}
        var rows=db.queryForList("SELECT username,display_name,password_hash,is_admin,credential_revision FROM user_accounts WHERE username=? AND enabled AND NOT deleted",name);
        return rows.size()==1?Optional.of(rows.getFirst()):Optional.empty();
    }
    public Map<String,Object> status(String raw,long revision) {
        String name;try {name=username(raw);} catch(IllegalArgumentException invalid) {return Map.of("valid",false);}
        var rows=db.queryForList("SELECT username,display_name,is_admin,credential_revision FROM user_accounts WHERE username=? AND credential_revision=? AND enabled AND NOT deleted",name,revision);
        if(rows.size()!=1) return Map.of("valid",false);
        var result=new LinkedHashMap<String,Object>(rows.getFirst());result.put("valid",true);return result;
    }
}
