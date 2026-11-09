package cn.trial.videosearch.ingest;

import cn.trial.videosearch.attributes.ChineseSemantics;
import cn.trial.videosearch.support.CanonicalJson;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceReceiptsTest {
    private Map<String,Object> fixture()throws Exception{
        try(var input=getClass().getResourceAsStream("/inference-receipt-contract.json")){
            assertNotNull(input);return InferenceReceipts.parse(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private Map<String,Object> validate(Map<String,Object> receipt){return InferenceReceipts.validate(receipt,(String)receipt.get("image_sha256"),"contract-model","contract-zh-v1");}
    @Test void pythonReceiptVerifiesAcrossCanonicalJsonImplementations()throws Exception{
        var receipt=fixture();var result=validate(receipt);
        InferenceReceipts.matchObjects(result,List.of());
        assertEquals("1.0",result.get("schema_version"));assertFalse(receipt.containsKey("api_key"));
        assertThrows(IllegalArgumentException.class,()->InferenceReceipts.validate(receipt,"0".repeat(64),"contract-model","contract-zh-v1"));
        assertThrows(IllegalArgumentException.class,()->InferenceReceipts.validate(receipt,(String)receipt.get("image_sha256"),"other-model","contract-zh-v1"));
    }
    @Test void modifiedContentCannotBeAcceptedEvenWithANewOuterChecksum()throws Exception{
        for(String field:List.of("request_json","response_json","schema_json")){
            var receipt=fixture();receipt.put(field,receipt.get(field)+" ");receipt.remove("receipt_sha256");
            receipt.put("receipt_sha256",CanonicalJson.encode(receipt,InferenceReceipts.MAX_BYTES).sha256());
            assertThrows(IllegalArgumentException.class,()->validate(receipt));
        }
        var receipt=fixture();receipt.put("api_key","unexpected");assertThrows(IllegalArgumentException.class,()->validate(receipt));
    }
    @Test void persistedSemanticsMustBeChineseWhileDedicatedOcrIsOutsideThisContract(){
        for(String text:List.of("white","a vehicle driving along the road"," "))assertThrows(IllegalArgumentException.class,()->ChineseSemantics.requireText(text));
        assertDoesNotThrow(()->ChineseSemantics.requireText("白色 SUV 驶过路口"));
        assertThrows(IllegalArgumentException.class,()->ChineseSemantics.requireAttributes(Map.of("carrying",List.of("backpack"))));
        assertDoesNotThrow(()->ChineseSemantics.requireAttributes(Map.of("carrying",List.of("黑色背包"))));
    }
}
