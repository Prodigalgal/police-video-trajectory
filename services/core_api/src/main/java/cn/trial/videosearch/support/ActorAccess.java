package cn.trial.videosearch.support;

import cn.trial.videosearch.admin.AdminService;
import cn.trial.videosearch.admin.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Internal business APIs require the current account revision as well as the service token. */
@Component
public class ActorAccess {
    private final UserService users;
    private final byte[] expected;
    public ActorAccess(UserService users,@Value("${VIDEO_ADMIN_TOKEN:}")String token){
        this.users=users;this.expected=token.isBlank()?null:("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    public void require(String authorization,String actor,Long revision,boolean admin){
        if(expected==null||authorization==null||authorization.length()>1024||!MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8))
            ||actor==null||revision==null||revision<1||!Boolean.TRUE.equals(users.status(actor,revision).get("valid")))throw new AdminService.Rejected(401,"unauthorized");
        if(admin)users.requireAdmin(actor);
    }
}
