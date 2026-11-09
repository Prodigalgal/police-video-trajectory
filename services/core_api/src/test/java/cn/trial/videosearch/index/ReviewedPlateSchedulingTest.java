package cn.trial.videosearch.index;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewedPlateSchedulingTest {
    @Test void publicationExecutorDoesNotOverrideComponentOrDefaultScheduler(){
        try(var context=new AnnotationConfigApplicationContext()){
            context.setAllowBeanDefinitionOverriding(false);
            context.registerBean(ReviewedPlatePublisher.class,()->mock(ReviewedPlatePublisher.class));
            context.register(ReviewedPlateScheduler.class,ReviewedPlateScheduler.Scheduling.class);
            context.refresh();
            assertInstanceOf(ReviewedPlateScheduler.class,context.getBean("reviewedPlateScheduler"));
            var publication=context.getBean("reviewedPlatePublicationExecutor",ThreadPoolTaskScheduler.class);
            assertNotSame(publication,context.getBean("taskScheduler",ThreadPoolTaskScheduler.class));
            assertEquals(1,publication.getScheduledThreadPoolExecutor().getCorePoolSize());
        }
    }
}
