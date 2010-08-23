/**
 *  Copyright 2003-2010 Terracotta, Inc.
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

package net.sf.ehcache.constructs;

import java.util.Properties;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;

/**
 * 
 * @author Abhishek Sanoujam
 * 
 */
public class MockDecoratorFactory extends CacheDecoratorFactory {

    @Override
    public Ehcache createDecoratedEhcache(Ehcache cache, Properties properties) {
        return new DecoratedTestEhcache(cache, properties, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Ehcache createDefaultDecoratedEhcache(Ehcache cache, Properties properties) {
        return new DecoratedTestEhcache(cache, properties, true);
    }

    public static class DecoratedTestEhcache extends EhcacheDecoratorAdapter {

        private final Properties properties;
        private final String name;

        public DecoratedTestEhcache(Ehcache underlyingCache, Properties properties, boolean forDefaultCache) {
            super(underlyingCache);
            this.properties = properties;
            String tmpName = properties.getProperty("name");
            if (tmpName == null || tmpName.trim().equalsIgnoreCase("")) {
                throw new CacheException("MockDecoratorFactory should be configured with a mandatory name property - " + properties);
            }
            if (forDefaultCache) {
                tmpName = CacheDecoratorFactory.generateDefaultDecoratedCacheName(underlyingCache, tmpName);
            }
            this.name = tmpName;
        }

        public Properties getProperties() {
            return properties;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String toString() {
            return "DecoratedTestEhcache[name=" + name + "]";
        }

    }

}
