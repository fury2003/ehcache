/**
 *  Copyright 2003-2009 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package net.sf.ehcache.hibernate;

import java.net.URL;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.ehcache.CacheManager;

import org.hibernate.cache.CacheException;
import org.hibernate.cfg.Settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A singleton EhCacheRegionFactory implementation.
 *
 * @author Chris Dennis
 * @author Greg Luck
 * @author Emmanuel Bernard
 */
public class SingletonEhCacheRegionFactory extends AbstractEhcacheRegionFactory {

    private static final Logger LOG = LoggerFactory.getLogger(SingletonEhCacheRegionFactory.class);

    private static final AtomicInteger REFERENCE_COUNT = new AtomicInteger();
    
    /**
     * Returns a representation of the singleton EhCacheRegionFactory
     */
    public SingletonEhCacheRegionFactory(Properties prop) {
        super();
    }

    /**
     * {@inheritDoc}
     */
    public void start(Settings settings, Properties properties) throws CacheException {
        String configurationResourceName = null;
        if (properties != null) {
            configurationResourceName = (String) properties.get(NET_SF_EHCACHE_CONFIGURATION_RESOURCE_NAME);
        }
        if (configurationResourceName == null || configurationResourceName.length() == 0) {
            manager = CacheManager.create();
            REFERENCE_COUNT.incrementAndGet();
        } else {
            if (!configurationResourceName.startsWith("/")) {
                configurationResourceName = "/" + configurationResourceName;
                    LOG.debug("prepending / to {}. It should be placed in the rootof the classpath rather than in a package.",
                            configurationResourceName);
            }
            URL url = loadResource(configurationResourceName);
            manager = CacheManager.create(url);
            REFERENCE_COUNT.incrementAndGet();
        }
        mbeanRegistrationHelper.registerMBean(manager, properties);
    }

    /**
     * {@inheritDoc}
     */
    public void stop() {
        if (manager != null) {
            if (REFERENCE_COUNT.decrementAndGet() == 0) {
                manager.shutdown();
            }
            manager = null;
        }
    }
}
