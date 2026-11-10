package cn.trial.videosearch.support;

import cn.trial.videosearch.models.ManagedCatalogStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

public final class CatalogTestSources {
    private CatalogTestSources(){}
    public static ObjectProvider<ManagedCatalogStore> none(){return new StaticListableBeanFactory().getBeanProvider(ManagedCatalogStore.class);}
    public static ObjectProvider<ManagedCatalogStore> of(ManagedCatalogStore store){
        var beans=new StaticListableBeanFactory();beans.addBean("managedCatalog",store);return beans.getBeanProvider(ManagedCatalogStore.class);
    }
}
