package cn.trial.videosearch.index;

import cn.trial.videosearch.http.SecureJsonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Bounded read-only access to the configured observation projection. */
@Service
public class ObservationIndexReader {
    private final SecureJsonClient client;
    private final String index,authorization;
    public ObservationIndexReader(@Value("${VIDEO_ES_URL:}")String url,@Value("${VIDEO_ES_CA:}")String ca,
            @Value("${VIDEO_ES_USER:}")String user,@Value("${VIDEO_ES_PASSWORD:}")String password,
            @Value("${VIDEO_ES_INDEX:police-video-observations}")String index){
        this.client=new SecureJsonClient(url,ca);this.index=SecureJsonClient.validateIndex(index);
        if(user.isBlank()||password.isBlank())throw new IllegalArgumentException("Index read credentials required");
        this.authorization="Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8));
    }
    public Map<String,Object> read(List<UUID> observations){
        if(observations==null||observations.isEmpty()||observations.size()>25||new HashSet<>(observations).size()!=observations.size()||observations.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Bounded observation IDs required");
        return client.post("/"+index+"/_mget",Map.of("ids",observations.stream().map(UUID::toString).toList()),authorization,Duration.ofSeconds(5));
    }
}
