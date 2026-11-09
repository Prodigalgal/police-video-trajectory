package cn.trial.videosearch.admin;

import org.junit.jupiter.api.Test;
import cn.trial.videosearch.support.ActorAccess;
import cn.trial.videosearch.support.BusinessEndpoint;
import cn.trial.videosearch.support.CanonicalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRegistryTest {
    private Map<String,Object> provider(boolean enabled){return Map.of("id","approved","label","已配置提供商","base_url","https://provider.invalid/v1","credential_file","provider.token","protocols",List.of("chat_completions","responses"),"roles",List.of("text","vision"),"activation_allowed",enabled);}
    private Object catalog(Map<String,Object> provider){return Map.of("schema_version","model-providers-v1","providers",List.of(provider));}
    @Test void providerCatalogRejectsUnknownFieldsAndUnsafeEndpointAndCredentialReference(){
        assertEquals(1,ModelProviders.parse(catalog(provider(true))).size());
        for(var update:List.of(Map.of("base_url","http://provider.invalid"),Map.of("base_url","https://secret@provider.invalid"),Map.of("base_url","https://provider.invalid/v1?key=bad"),Map.of("credential_file","../private"),Map.of("roles",List.of("face")),Map.of("api_key","must-not-be-here"))){
            var invalid=new HashMap<>(provider(true));invalid.putAll(update);assertThrows(IllegalArgumentException.class,()->ModelProviders.parse(catalog(invalid)));
        }
    }
    @Test void revisionsFreezeApprovedProviderAndKeepSavedOnlyProviderRepresentable(){
        var provider=ModelProviders.parse(catalog(provider(false))).getFirst();
        var body=new ModelRegistry.Definition("模型","approved","model","responses",List.of("text","vision"),"file","tool",false,"");
        var value=ModelRegistry.definition(body,provider);
        assertEquals("https://provider.invalid/v1",value.get("base_url"));assertEquals("provider.token",value.get("credential_file"));assertFalse(value.containsKey("api_key"));
        assertThrows(IllegalArgumentException.class,()->ModelRegistry.definition(new ModelRegistry.Definition("模型","approved","model","responses",List.of("text","text"),"file","tool",false,""),provider));
        assertThrows(IllegalArgumentException.class,()->ModelRegistry.definition(new ModelRegistry.Definition("模型","approved","model","responses",List.of("text"),"file","tool",true,""),provider));
    }
    @Test void readerCredentialCannotUseAdminApisAndNonAdminCannotWrite(){
        var registry=mock(ModelRegistry.class);var users=mock(UserService.class);var controller=new ModelController(registry,new BusinessEndpoint(new ActorAccess(users,"admin-fixture")),"reader-fixture");
        assertEquals(401,controller.list("Bearer reader-fixture","admin",1L).block().getStatusCode().value());verifyNoInteractions(registry,users);
        when(users.status("reader",1)).thenReturn(Map.of("valid",true));
        doThrow(new AdminService.Rejected(403,"admin_required")).when(users).requireAdmin("reader");
        assertEquals(403,controller.list("Bearer admin-fixture","reader",1L).block().getStatusCode().value());verifyNoInteractions(registry);
        when(users.status("reader",2)).thenReturn(Map.of("valid",false));assertEquals(401,controller.list("Bearer admin-fixture","reader",2L).block().getStatusCode().value());
        when(registry.runtime()).thenReturn(Map.of("revision",1));
        assertEquals(200,controller.runtime("Bearer reader-fixture").block().getStatusCode().value());
    }
    @Test void runtimeRejectsRevokedProviderRoleOrProtocolForPreviouslySavedRevision(){
        var db=mock(JdbcTemplate.class);var providers=mock(ModelProviders.class);var registry=new ModelRegistry(db,mock(UserService.class),providers);
        var binding=Map.of("profile_id",UUID.randomUUID().toString(),"version",1,"definition",Map.of("provider","approved","base_url","https://provider.invalid/v1","credential_file","provider.token","protocol","responses"));
        var bindings=new LinkedHashMap<String,Object>();bindings.put("text",null);bindings.put("vision",binding);
        when(db.queryForMap("SELECT revision,bindings::text,updated_at FROM model_bindings WHERE singleton")).thenReturn(Map.of("revision",1L,"bindings",CanonicalJson.encode(bindings,8192).payload()));
        when(providers.current()).thenReturn(List.of(new ModelProviders.Provider("approved","已配置","https://provider.invalid/v1","provider.token",Set.of("responses"),Set.of("text"),true)));
        assertThrows(AdminService.Rejected.class,registry::runtime);
        when(providers.current()).thenReturn(List.of(new ModelProviders.Provider("approved","已配置","https://provider.invalid/v1","provider.token",Set.of("chat_completions"),Set.of("vision"),true)));
        assertThrows(AdminService.Rejected.class,registry::runtime);
        when(providers.current()).thenReturn(List.of(new ModelProviders.Provider("approved","已配置","https://provider.invalid/v1","provider.token",Set.of("responses"),Set.of("vision"),true)));
        assertEquals(1L,registry.runtime().get("revision"));
    }
}
