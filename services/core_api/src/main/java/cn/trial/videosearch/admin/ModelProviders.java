package cn.trial.videosearch.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Operations-owned endpoints and credential references, never credential values. */
@Service
public class ModelProviders {
    private final Path path;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Logger LOG=LoggerFactory.getLogger(ModelProviders.class);
    private boolean failed;
    private volatile List<Provider> snapshot;
    public record Provider(String id,String label,String baseUrl,String credentialFile,Set<String> protocols,Set<String> roles,boolean activationAllowed) {
        Map<String,Object> publicView(){return Map.of("id",id,"label",label,"base_url",baseUrl,"protocols",new TreeSet<>(protocols),"roles",new TreeSet<>(roles),"activation_allowed",activationAllowed);}
    }
    public ModelProviders(@Value("${VIDEO_MODEL_PROVIDERS_FILE:}") String file){path=file.isBlank()?null:Path.of(file);refresh();}
    public List<Provider> current(){
        var available=snapshot;if(available==null)throw new AdminService.Rejected(503,"model_providers_unavailable");return available;
    }
    @Scheduled(fixedDelayString="${VIDEO_MODEL_PROVIDERS_REFRESH_MS:10000}")
    public void refresh(){
        if(path==null){snapshot=List.of();return;}
        try(var stream=Files.newInputStream(path)){
            byte[] raw=stream.readNBytes(65537);if(raw.length>65536)throw new IllegalArgumentException();
            snapshot=parse(JSON.readValue(raw,Object.class));if(failed)LOG.info("Model provider catalog recovered");failed=false;
        }catch(Exception error){snapshot=null;if(!failed)LOG.warn("Model provider catalog unavailable ({})",error.getClass().getSimpleName());failed=true;}
    }
    static List<Provider> parse(Object raw){
        if(!(raw instanceof Map<?,?> root)||!root.keySet().equals(Set.of("schema_version","providers"))||!"model-providers-v1".equals(root.get("schema_version"))||!(root.get("providers") instanceof List<?> values)||values.size()>16)throw new IllegalArgumentException();
        var result=new ArrayList<Provider>();var ids=new HashSet<String>();
        for(Object entry:values){
            if(!(entry instanceof Map<?,?> value)||!value.keySet().equals(Set.of("id","label","base_url","credential_file","protocols","roles","activation_allowed")))throw new IllegalArgumentException();
            String id=text(value.get("id"),80),label=text(value.get("label"),100),url=text(value.get("base_url"),500),file=text(value.get("credential_file"),80);
            var uri=URI.create(url);
            if(!id.matches("[a-z][a-z0-9_-]{0,79}")||!ids.add(id)||!file.matches("[a-z0-9][a-z0-9_.-]{0,79}")||file.contains("..")||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()>65535||url.endsWith("/")||!(value.get("activation_allowed") instanceof Boolean enabled))throw new IllegalArgumentException();
            result.add(new Provider(id,label,url,file,strings(value.get("protocols"),Set.of("chat_completions","responses")),strings(value.get("roles"),Set.of("text","vision")),enabled));
        }
        return List.copyOf(result);
    }
    static Set<String> strings(Object raw,Set<String> allowed){
        if(!(raw instanceof List<?> list)||list.isEmpty()||list.size()>allowed.size()||!allowed.containsAll(list)||new HashSet<>(list).size()!=list.size())throw new IllegalArgumentException();
        var result=new HashSet<String>();for(Object value:list)result.add((String)value);return Set.copyOf(result);
    }
    static String text(Object raw,int max){if(!(raw instanceof String text)||text.isBlank()||text.length()>max||text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return text.strip();}
}
