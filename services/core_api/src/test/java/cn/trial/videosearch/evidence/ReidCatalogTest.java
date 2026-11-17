package cn.trial.videosearch.evidence;

import cn.trial.videosearch.models.ManagedCatalogStore;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static cn.trial.videosearch.support.CatalogTestSources.none;
import static org.junit.jupiter.api.Assertions.*;

/** Feature-space and operator-binding contracts, not surveillance accuracy labels. */
class ReidCatalogTest {
    @TempDir Path directory;
    @Test void nativeOsnetContractsKeepPersonVehicleShapesAndChannelsSeparate(){
        for(String type:List.of("person","vehicle")){
            var value=new LinkedHashMap<>(specialized(type));value.put("adapter","onnx_osnet_v1");value.put("normalization","raw_0_255");
            value.put("dimensions","person".equals(type)?256:512);value.put("preprocessing","person".equals(type)?"roi_resize_256x128_bgr_v1":"roi_resize_208x208_rgb_v1");
            assertTrue(AppearanceCatalog.parse(value).supports(type));
            value.put("normalization","imagenet");assertThrows(IllegalArgumentException.class,()->AppearanceCatalog.parse(value));
        }
    }
    private static Map<String,Object> general(){return Map.of("profile","general","adapter","onnx_crop_v1","model_file","general.onnx","model_sha256","a".repeat(64),"dimensions",384,"normalization","imagenet","output","cls_token","preprocessing","roi_letterbox_224_rgb_v1");}
    private static Map<String,Object> specialized(String type){
        var value=new LinkedHashMap<String,Object>();value.put("profile",type+"-reid");value.put("adapter","onnx_reid_v1");value.put("model_file",type+"-reid.onnx");value.put("model_sha256","b".repeat(64));value.put("dimensions",512);value.put("normalization","imagenet");value.put("output","embedding");value.put("preprocessing","person".equals(type)?"roi_resize_256x128_rgb_v1":"roi_resize_256x256_rgb_v1");value.put("target_types",List.of(type));return value;
    }
    private static LinkedHashMap<String,Object> root(){return new LinkedHashMap<>(Map.of("schema_version","appearance-catalog-v1","revision",1,"active","general","profiles",List.of(general(),specialized("person"),specialized("vehicle"))));}
    private AppearanceCatalog catalog(Path path,Map<String,Object> value)throws Exception{Files.writeString(path,CanonicalJson.encode(value,32768).payload());return new AppearanceCatalog(path.toString(),none());}
    @Test void typedHotSwitchPreservesLegacySpaceAndFrozenInFlightSelection()throws Exception{
        Path path=directory.resolve("catalog.json");var value=root();var catalog=catalog(path,value);var before=catalog.current();String digest=before.selected().sha256();
        value.put("revision",2);value.put("active_by_type",Map.of("person","person-reid","vehicle","vehicle-reid"));Files.writeString(path,CanonicalJson.encode(value,32768).payload());var after=catalog.current();
        assertEquals("general",after.selected().id());assertEquals(digest,after.selected().sha256());assertEquals("person-reid",after.selected("person").id());assertEquals("vehicle-reid",after.selected("vehicle").id());
        assertEquals("general",before.selected("person").id());assertFalse(after.selected("person").supports("vehicle"));assertEquals(512,after.selected("person").dimensions());
        value.put("active_by_type",Map.of("person","vehicle-reid","vehicle","vehicle-reid"));value.put("revision",3);Files.writeString(path,CanonicalJson.encode(value,32768).payload());assertThrows(InputRejected.class,catalog::current);
    }
    @Test void bindingsRemainAtomicAndLegacyCommandsResetBothTypedSelections(){
        var definition=root();var base=new ManagedCatalogStore.Baseline("appearance",1,CanonicalJson.encode(definition,32768).sha256(),definition);
        var bindings=Map.of("appearance","general","person","person-reid","vehicle","vehicle-reid");var typed=ManagedCatalogStore.materialize(base,bindings,2);
        assertEquals(bindings,ManagedCatalogStore.bindings("appearance",typed));
        var saved=new ManagedCatalogStore.Saved(2,base.sha256(),CanonicalJson.encode(typed,131072).sha256(),typed);assertEquals(typed,ManagedCatalogStore.resolve(base,saved));
        var legacy=ManagedCatalogStore.materialize(new ManagedCatalogStore.Baseline("appearance",2,base.sha256(),typed),Map.of("appearance","general"),3);assertFalse(legacy.containsKey("active_by_type"));
        assertThrows(InputRejected.class,()->ManagedCatalogStore.materialize(base,Map.of("appearance","general","person","vehicle-reid","vehicle","vehicle-reid"),2));
        assertThrows(InputRejected.class,()->ManagedCatalogStore.materialize(base,Map.of("appearance","person-reid"),2));
        assertThrows(InputRejected.class,()->ManagedCatalogStore.materialize(base,Map.of("appearance","general","person","person-reid"),2));
    }
    @Test void reidAdapterRejectsUnboundedDimensionsWrongShapeAndArbitraryOutputs(){
        for(var update:List.of(Map.<String,Object>of("dimensions",2048),Map.<String,Object>of("target_types",List.of("person","vehicle")),Map.<String,Object>of("output","arbitrary"),Map.<String,Object>of("preprocessing","roi_resize_256x256_rgb_v1"))){var definition=new LinkedHashMap<>(specialized("person"));definition.putAll(update);assertThrows(IllegalArgumentException.class,()->AppearanceCatalog.parse(definition));}
        assertEquals("person-reid",AppearanceCatalog.parse(specialized("person")).id());
    }
}
