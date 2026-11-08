package cn.trial.videosearch.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    @Test void accountNamesAndCredentialEncodingsAreBounded() {
        assertEquals("admin",UserService.username(" Admin "));
        assertThrows(IllegalArgumentException.class,()->UserService.username("../admin"));
        assertThrows(IllegalArgumentException.class,()->UserService.hash("plaintext-password"));
        assertThrows(IllegalArgumentException.class,()->UserService.display("name\ncommand"));
    }
    @Test void publicUserListDoesNotReturnPasswordHashes() {
        var db=mock(JdbcTemplate.class);var row=new HashMap<String,Object>();
        row.put("username","viewer");row.put("display_name","用户");row.put("enabled",true);row.put("is_admin",false);row.put("row_version",1L);row.put("password_hash","must-remain-private");
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        var result=new UserService(db).list(50,null);
        assertFalse(result.toString().contains("password_hash"));assertFalse(result.toString().contains("must-remain-private"));
    }
    @Test void defaultAdministratorCannotBeDisabledOrDeleted() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(contains("username=? AND enabled AND is_admin"),eq(Long.class),eq("admin"))).thenReturn(1L);
        when(db.queryForList(contains("FOR UPDATE"),any(Object[].class))).thenReturn(List.of(Map.of("username","admin","row_version",1L,"enabled",true,"is_admin",true)));
        var users=new UserService(db);
        assertEquals("protected_account",assertThrows(AdminService.Rejected.class,()->users.update("admin",new UserService.Update("管理员",false,true,1),"admin")).code);
        assertEquals("protected_account",assertThrows(AdminService.Rejected.class,()->users.remove("admin",1,"admin")).code);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleUserEditsCannotOverwriteNewerCredentials() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForObject(contains("username=? AND enabled AND is_admin"),eq(Long.class),eq("admin"))).thenReturn(1L);
        when(db.queryForList(contains("FOR UPDATE"),any(Object[].class))).thenReturn(List.of(Map.of("username","viewer","row_version",3L)));
        var users=new UserService(db);
        assertEquals("user_revision_conflict",assertThrows(AdminService.Rejected.class,()->users.update("viewer",new UserService.Update("用户",true,false,2),"admin")).code);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void internalCredentialsRequireTheDedicatedServiceToken() {
        var service=mock(UserService.class);var controller=new UserController(service,"private-token");
        assertEquals(401,controller.credential(null,new UserController.Credential("admin")).block().getStatusCode().value());
        verifyNoInteractions(service);
    }
}
