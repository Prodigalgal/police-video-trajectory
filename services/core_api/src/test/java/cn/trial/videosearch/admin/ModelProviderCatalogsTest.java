package cn.trial.videosearch.admin;

import cn.trial.videosearch.evidence.InputRejected;
import cn.trial.videosearch.http.SecureJsonClient;
import cn.trial.videosearch.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelProviderCatalogsTest {
    private ModelProviders.Provider provider(boolean active,Set<String> roles){return new ModelProviders.Provider("approved","已配置提供商","https://provider.invalid/v1","approved.token",Set.of("responses","chat_completions"),roles,active);}
    private ModelProviders.Provider provider(){return provider(true,Set.of("text","vision"));}
    private Map<String,Object> model(){
        var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("id","candidate","name","候选模型","input_media",List.of("text","image"),
            "declared_roles",List.of("text","vision"),"allowed_roles",List.of("text","vision"),"protocols",List.of("responses"),"streaming",true,"reasoning_levels",List.of("none"),"tools",true,"inference_verified",false));
        value.put("analysis_reason",null);return value;
    }
    private Map<String,Object> catalog(List<?> models){
        var value=new LinkedHashMap<String,Object>();value.putAll(Map.of("schema_version","provider-model-catalog-v1","provider","approved","configuration_sha256",ModelProviderCatalogs.fingerprint(provider()),
            "state","ready","checked_at","2026-11-10T12:00:00Z","cached",false,"models",models,"catalog_sha256",CanonicalJson.encode(models,2*1024*1024).sha256(),"inference_verified",false));
        value.put("diagnostics",null);return value;
    }
    private void invalid(Map<String,Object> value){var failure=assertThrows(InputRejected.class,()->ModelProviderCatalogs.validate(value,provider()));assertEquals(503,failure.status);assertEquals("model_catalog_invalid",failure.code);}

    @Test void discoveryUsesFixedGatewayBoundAndAuditsOnlyAfterValidResponse(){
        var providers=mock(ModelProviders.class);var db=mock(JdbcTemplate.class);when(providers.current()).thenReturn(List.of(provider()));var response=catalog(List.of(model()));
        try(var clients=mockConstruction(SecureJsonClient.class)){
            var service=new ModelProviderCatalogs(providers,db,"https://gateway.invalid","unused","protocol-token");var gateway=clients.constructed().getFirst();
            when(gateway.get(anyString(),anyString(),any())).thenAnswer(invocation->{verifyNoInteractions(db);return response;});
            assertSame(response,service.read("approved",true,"admin"));
            verify(gateway).get("/internal/v1/providers/approved/models?refresh=true","Bearer protocol-token",Duration.ofSeconds(28));
            verify(db).update(anyString(),eq("admin"),argThat((String payload)->payload.contains("catalog_sha256")&&!payload.contains("protocol-token")&&!payload.contains("base_url")),any(UUID.class));
        }
    }
    @Test void disabledProviderAndConfigurationRaceCannotExposeOrAuditOldDirectory(){
        var providers=mock(ModelProviders.class);var db=mock(JdbcTemplate.class);
        try(var clients=mockConstruction(SecureJsonClient.class)){
            var service=new ModelProviderCatalogs(providers,db,"https://gateway.invalid","unused","protocol-token");var gateway=clients.constructed().getFirst();
            when(providers.current()).thenReturn(List.of(provider(false,Set.of("text"))));
            assertEquals(409,assertThrows(InputRejected.class,()->service.read("approved",true,"admin")).status);verifyNoInteractions(gateway,db);
            when(providers.current()).thenReturn(List.of(provider())).thenReturn(List.of(provider(true,Set.of("text"))));
            when(gateway.get(anyString(),anyString(),any())).thenReturn(catalog(List.of(model())));
            assertEquals("model_catalog_configuration_changed",assertThrows(InputRejected.class,()->service.read("approved",false,"admin")).code);verifyNoInteractions(db);
        }
    }
    @Test void corruptedDigestNullEnumsAndUndeclaredRolesFailClosed(){
        var valid=catalog(List.of(model()));assertDoesNotThrow(()->ModelProviderCatalogs.validate(valid,provider()));
        var wrongDigest=new LinkedHashMap<>(valid);wrongDigest.put("catalog_sha256","0".repeat(64));invalid(wrongDigest);
        var missingState=new LinkedHashMap<>(valid);missingState.put("state",null);invalid(missingState);
        List<Consumer<Map<String,Object>>> mutations=List.of(m->m.put("allowed_roles",Arrays.asList((Object)null)),m->m.put("input_media",List.of("text")),
            m->m.put("declared_roles",List.of("face")),m->m.put("streaming",false),m->m.put("analysis_reason","media_endpoint_required"),m->m.put("private","unsafe"));
        for(var mutation:mutations){var changed=model();mutation.accept(changed);invalid(catalog(List.of(changed)));}
        invalid(catalog(List.of(model(),model())));
    }
    @Test void failuresHaveWhitelistedDiagnosticFieldsAndNoModels(){
        var value=catalog(List.of());value.put("state","unavailable");value.put("diagnostics",Map.of("category","credential_rejected","upstream_status",401,"upstream_error_code","credential_rejected"));
        assertDoesNotThrow(()->ModelProviderCatalogs.validate(value,provider()));
        value.put("diagnostics",Map.of("category","credential_rejected","upstream_status",401,"message","private upstream"));invalid(value);
        value.put("diagnostics",Map.of("category","catalog_timeout","upstream_status",200.1));invalid(value);
        value.put("diagnostics",Map.of("category","catalog_timeout","upstream_request_id","private id"));invalid(value);
    }
    @Test void readerCredentialNonAdminAndOldActorCannotReachGateway(){
        var catalogs=mock(ModelProviderCatalogs.class);var users=mock(UserService.class);var controller=new ModelProviderCatalogController(new BusinessEndpoint(new ActorAccess(users,"admin-fixture")),catalogs);
        assertEquals(401,controller.read("Bearer reader-fixture","admin",1L,"approved").block().getStatusCode().value());verifyNoInteractions(catalogs,users);
        when(users.status("reader",1)).thenReturn(Map.of("valid",true));doThrow(new AdminService.Rejected(403,"admin_required")).when(users).requireAdmin("reader");
        assertEquals(403,controller.refresh("Bearer admin-fixture","reader",1L,"approved").block().getStatusCode().value());verifyNoInteractions(catalogs);
        when(users.status("admin",2)).thenReturn(Map.of("valid",false));assertEquals(401,controller.read("Bearer admin-fixture","admin",2L,"approved").block().getStatusCode().value());verifyNoInteractions(catalogs);
    }
}
