package cn.trial.videosearch.index;

import cn.trial.videosearch.http.SecureJsonClient;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewedPlateIndexReaderTest {
    @Test void plateLookupUsesReaderCredentialsConfiguredSiblingAndFiveSecondBound(){
        try(var clients=mockConstruction(SecureJsonClient.class)){
            var reader=new ObservationIndexReader("https://index.invalid","unused","protocol-reader","protocol-password","police-video-observations-trial");UUID id=UUID.randomUUID();
            reader.readReviewedPlates(List.of(id));
            verify(clients.constructed().getFirst()).post(eq("/police-video-observations-trial-plates/_mget?realtime=false"),eq(Map.of("ids",List.of(id.toString()))),startsWith("Basic "),eq(Duration.ofSeconds(5)));
            assertThrows(IllegalArgumentException.class,()->reader.readReviewedPlates(List.of(id,id)));
            assertThrows(IllegalArgumentException.class,()->reader.readReviewedPlates(Collections.nCopies(26,id)));
            assertThrows(IllegalArgumentException.class,()->reader.readReviewedPlates(List.of()));
            verifyNoMoreInteractions(clients.constructed().getFirst());
        }
    }
}
