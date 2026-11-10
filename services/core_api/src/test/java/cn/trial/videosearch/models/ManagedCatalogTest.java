package cn.trial.videosearch.models;

import cn.trial.videosearch.evidence.*;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static cn.trial.videosearch.support.CatalogTestSources.of;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Nonvisual configuration contracts; these profiles are not installed model claims. */
class ManagedCatalogTest {
    @TempDir Path directory;
    private static Map<String,Object> processing(){
        var profile=Map.<String,Object>of("profile","plate-contract","adapter","specialist_sse_v1","capabilities",List.of("license_plate"),
            "base_url","https://specialist.invalid","token_file","contract.token","ca_file","contract.crt","model_id","contract-only","contract","specialist-result-v1","streaming",true,"timeout_seconds",360);
        var disabled=new LinkedHashMap<String,Object>();disabled.put("enabled",false);disabled.put("profile",null);
        return Map.of("schema_version","processing-catalog-v2","revision",1,"modules",Map.of("license_plate",Map.of("enabled",true,"profile","plate-contract"),"text_ocr",disabled,"face_quality",Map.of("enabled",false)),"profiles",List.of(profile));
    }
    private static Map<String,Object> appearanceProfile(String id,int dimensions){return Map.of("profile",id,"adapter","onnx_crop_v1","model_file",id+".onnx","model_sha256","a".repeat(64),"dimensions",dimensions,"normalization",dimensions==384?"imagenet":"clip","output",dimensions==384?"cls_token":"image_embeds","preprocessing","roi_letterbox_224_rgb_v1");}
    private static ManagedCatalogStore.Saved saved(ManagedCatalogStore.Baseline base,Map<String,String> bindings,long revision){
        var definition=ManagedCatalogStore.materialize(base,bindings,revision);return new ManagedCatalogStore.Saved(revision,base.sha256(),CanonicalJson.encode(definition,131072).sha256(),definition);
    }
    private static Map<String,String> off(){var bindings=new LinkedHashMap<String,String>();bindings.put("license_plate",null);bindings.put("text_ocr",null);return bindings;}
    @Test void disablingNewBindingsDoesNotRewriteTheFrozenRoute()throws Exception{
        var path=directory.resolve("processing.json");Files.writeString(path,CanonicalJson.encode(processing(),131072).payload());
        var state=new AtomicReference<ManagedCatalogStore.Saved>();var store=mock(ManagedCatalogStore.class);
        when(store.resolve(any())).thenAnswer(call->ManagedCatalogStore.resolve(call.getArgument(0),state.get()));
        var catalog=new ProcessingCatalog(path.toString(),of(store));var old=catalog.binding("license_plate","");String frozen=old.encoded().sha256();
        var baseline=catalog.installed();state.set(saved(baseline,off(),2));
        assertEquals("specialist_module_disabled",assertThrows(InputRejected.class,()->catalog.binding("license_plate","")).code);
        assertEquals(frozen,old.encoded().sha256());assertEquals("plate-contract",old.profile());assertEquals(2,catalog.current().revision());
    }
    @Test void operatorDocumentsCannotChangeTransportSecretsOrOtherModules(){
        var root=processing();var base=new ManagedCatalogStore.Baseline("processing",1,CanonicalJson.encode(root,131072).sha256(),root);
        var good=saved(base,off(),2);assertNotNull(ManagedCatalogStore.resolve(base,good));
        var changed=new LinkedHashMap<>(good.definition());changed.put("profiles",List.of(Map.of("profile","plate-contract","base_url","https://untrusted.invalid")));
        var bad=new ManagedCatalogStore.Saved(2,base.sha256(),CanonicalJson.encode(changed,131072).sha256(),changed);
        assertEquals(503,assertThrows(InputRejected.class,()->ManagedCatalogStore.resolve(base,bad)).status);
        assertThrows(InputRejected.class,()->ManagedCatalogStore.materialize(base,Map.of("license_plate","not-installed","text_ocr","not-installed"),2));
    }
    @Test void appearanceSpacesStayImmutableAndFileChangesRequireRebinding()throws Exception{
        var path=directory.resolve("appearance.json");var original=new LinkedHashMap<String,Object>(Map.of("schema_version","appearance-catalog-v1","revision",1,"active","first","profiles",List.of(appearanceProfile("first",384),appearanceProfile("second",512))));
        Files.writeString(path,CanonicalJson.encode(original,32768).payload());
        var state=new AtomicReference<ManagedCatalogStore.Saved>();var store=mock(ManagedCatalogStore.class);
        when(store.resolve(any())).thenAnswer(call->ManagedCatalogStore.resolve(call.getArgument(0),state.get()));
        var catalog=new AppearanceCatalog(path.toString(),of(store));var old=catalog.current().selected();var baseline=catalog.installed();
        state.set(saved(baseline,Map.of("appearance","second"),2));assertEquals(512,catalog.current().selected().dimensions());assertEquals(384,old.dimensions());
        original.put("revision",2);Files.writeString(path,CanonicalJson.encode(original,32768).payload());
        assertEquals("model_catalog_source_changed",assertThrows(InputRejected.class,catalog::current).code);
        var refreshed=catalog.installed();state.set(saved(refreshed,Map.of("appearance","second"),3));assertEquals(3,catalog.current().revision());
    }
}
