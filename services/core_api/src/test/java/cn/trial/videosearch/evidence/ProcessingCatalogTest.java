package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingCatalogTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static Map<String,Object> profile(String id){return new LinkedHashMap<>(Map.of("profile",id,"adapter","specialist_sse_v1","capabilities",List.of("license_plate"),"base_url","https://example.invalid/recognize","token_file","token","ca_file","ca.pem","model_id",id,"contract","specialist-result-v1","streaming",true,"timeout_seconds",360));}
    private static Map<String,Object> value(int revision,String active){return Map.of("schema_version","processing-catalog-v1","revision",revision,"modules",Map.of("license_plate",Map.of("enabled",true,"profile",active)),"profiles",List.of(profile("a"),profile("b")));}
    private void write(Path path,Object value)throws Exception{Files.writeString(path,JSON.writeValueAsString(value));}

    @Test void hotSwitchKeepsOldBindingAndRejectsBrokenOrReusedRevision()throws Exception{
        Path file=root.resolve("catalog.json");write(file,value(1,"a"));var catalog=new ProcessingCatalog(file.toString());
        var a=catalog.binding("license_plate","");write(file,value(2,"b"));var b=catalog.binding("license_plate","");
        assertEquals("a",a.profile());assertEquals("b",b.profile());assertNotEquals(a.encoded().sha256(),b.encoded().sha256());
        write(file,value(2,"a"));assertEquals("processing_catalog_unavailable",assertThrows(InputRejected.class,()->catalog.binding("license_plate","")).code);
        Files.writeString(file,"{");assertThrows(InputRejected.class,catalog::current);
        write(file,value(3,"a"));assertEquals("a",catalog.binding("license_plate","").profile());
    }
    @Test void profileCannotChangeInPlaceAndDisabledModuleHasNoFallback()throws Exception{
        Path file=root.resolve("catalog.json");write(file,value(1,"a"));var catalog=new ProcessingCatalog(file.toString());catalog.current();
        var changed=new LinkedHashMap<String,Object>(value(2,"a"));var replacement=profile("a");replacement.put("model_id","replacement");changed.put("profiles",List.of(replacement,profile("b")));write(file,changed);
        assertThrows(InputRejected.class,catalog::current);
        changed=new LinkedHashMap<>(value(3,"a"));changed.put("modules",Map.of("license_plate",Map.of("enabled",false,"profile","a")));write(file,changed);
        assertEquals("specialist_module_disabled",assertThrows(InputRejected.class,()->catalog.binding("license_plate","legacy")).code);
    }
    @Test void onlyInstalledStreamingContractAndSafeCredentialReferencesAreAccepted(){
        for(String field:List.of("streaming","timeout_seconds","token_file","adapter")){
            var definition=profile("a");definition.put(field,switch(field){case "streaming"->false;case "timeout_seconds"->60;case "token_file"->"../token";default->"arbitrary_import";});
            assertThrows(RuntimeException.class,()->ProcessingCatalog.profile(definition));
        }
        var definition=profile("a");definition.put("base_url","http://example.invalid");assertThrows(RuntimeException.class,()->ProcessingCatalog.profile(definition));
    }
    @Test void legacyBindingDoesNotRequireAFile(){
        var catalog=new ProcessingCatalog("");var binding=catalog.binding("license_plate","legacy");
        assertFalse(catalog.configured());assertEquals("legacy",binding.profile());assertNull(binding.route());
    }
}
