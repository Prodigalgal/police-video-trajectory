package cn.trial.videosearch.geography;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.search.SearchOrchestrator;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;

@Service
public class CameraLocations {
    private final JdbcTemplate db;
    private final SearchOrchestrator scope;
    private final TransactionTemplate tx;
    private static final ObjectMapper JSON=new ObjectMapper();
    public CameraLocations(JdbcTemplate db,SearchOrchestrator scope,PlatformTransactionManager manager){this.db=db;this.scope=scope;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    public record Update(UUID requestId,long expectedRevision,Double longitude,Double latitude,String coordinateSystem,String address,String reason,String coordinateSource){}
    public List<Map<String,Object>> list(){
        var cameras=scope.readableCameras().stream().sorted().toList();
        if(cameras.isEmpty())return List.of();
        return db.queryForList("SELECT c.camera_id,c.display_name,c.enabled,g.longitude,g.latitude,COALESCE(g.coordinate_system,'GCJ-02') AS coordinate_system,COALESCE(g.coordinate_source,'configured') AS coordinate_source,COALESCE(g.address,'') AS address,COALESCE(g.revision,0) AS revision,g.updated_at FROM cameras c LEFT JOIN camera_locations g USING(camera_id) WHERE c.camera_id IN ("+String.join(",",Collections.nCopies(cameras.size(),"?"))+") ORDER BY c.camera_id",cameras.toArray()).stream().map(CameraLocations::external).toList();
    }
    public Map<String,Object> update(String camera,Update request,String actor){
        if(!scope.mayReadCamera(camera))throw new AdminService.Rejected(404,"camera_unavailable");
        validate(request);
        var encoded=CanonicalJson.encode(Map.of("camera",camera,"request",requestValues(request)),8192);
        return tx.execute(status->{
            db.execute("SELECT pg_advisory_xact_lock(918273692)");
            var locked=db.queryForList("SELECT camera_id FROM cameras WHERE camera_id=? FOR UPDATE",camera);
            if(locked.isEmpty())throw new AdminService.Rejected(404,"camera_unavailable");
            var prior=db.queryForList("SELECT actor_id,operation,request_sha256,response::text AS payload FROM application_command_receipts WHERE request_id=?",request.requestId());
            if(!prior.isEmpty()){
                var receipt=prior.getFirst();
                if(!actor.equals(receipt.get("actor_id"))||!"camera.location".equals(receipt.get("operation"))||!encoded.sha256().equals(receipt.get("request_sha256")))throw new AdminService.Rejected(409,"command_request_conflict");
                return object(JSON.readValue((String)receipt.get("payload"),Map.class));
            }
            Long version=db.queryForObject("SELECT COALESCE((SELECT revision FROM camera_locations WHERE camera_id=?),0)",Long.class,camera);
            if(version!=request.expectedRevision())throw new AdminService.Rejected(409,"location_revision_conflict");
            String source=request.coordinateSource()==null?db.queryForObject("SELECT COALESCE((SELECT coordinate_source FROM camera_locations WHERE camera_id=?),'configured')",String.class,camera):request.coordinateSource();
            db.update("INSERT INTO camera_locations(camera_id,longitude,latitude,address,coordinate_source,updated_by) VALUES(?,?,?,?,?,?) ON CONFLICT(camera_id) DO UPDATE SET longitude=excluded.longitude,latitude=excluded.latitude,address=excluded.address,coordinate_source=excluded.coordinate_source,updated_by=excluded.updated_by,revision=camera_locations.revision+1,updated_at=clock_timestamp()",camera,request.longitude(),request.latitude(),request.address().strip(),source,actor);
            var response=external(db.queryForList("SELECT camera_id,longitude,latitude,coordinate_system,coordinate_source,address,revision,updated_at FROM camera_locations WHERE camera_id=?",camera).getFirst());
            db.update("INSERT INTO application_command_receipts(request_id,operation,actor_id,request_sha256,response) VALUES(?,'camera.location',?,?,?::jsonb)",request.requestId(),actor,encoded.sha256(),CanonicalJson.encode(response,8192).payload());
            db.update("INSERT INTO audit_events(actor_id,action,result,request_id) VALUES(?,'camera.location',?,?)",actor,CanonicalJson.encode(Map.of("camera_id",camera,"revision",response.get("revision"),"reason",request.reason().strip()),8192).payload(),request.requestId());
            return response;
        });
    }
    private static void validate(Update request){
        if(request==null||request.requestId()==null||request.expectedRevision()<0||!"GCJ-02".equals(request.coordinateSystem())||request.address()==null||request.address().length()>500||request.reason()==null||request.reason().isBlank()||request.reason().length()>300
            ||request.coordinateSource()!=null&&!Set.of("configured","display_layout").contains(request.coordinateSource())
            ||(request.longitude()==null)!=(request.latitude()==null)||request.longitude()!=null&&(!Double.isFinite(request.longitude())||Math.abs(request.longitude())>180||!Double.isFinite(request.latitude())||Math.abs(request.latitude())>90))throw new IllegalArgumentException("Invalid camera location");
    }
    private static Map<String,Object> requestValues(Update request){var result=new LinkedHashMap<String,Object>();result.put("revision",request.expectedRevision());result.put("longitude",request.longitude());result.put("latitude",request.latitude());result.put("coordinate_system",request.coordinateSystem());result.put("coordinate_source",request.coordinateSource());result.put("address",request.address().strip());result.put("reason",request.reason().strip());return result;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<?,?> value){return (Map<String,Object>)value;}
    private static Map<String,Object> external(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();row.forEach((key,value)->result.put(key,value instanceof Timestamp time?time.toInstant().toString():value));return result;}
}
