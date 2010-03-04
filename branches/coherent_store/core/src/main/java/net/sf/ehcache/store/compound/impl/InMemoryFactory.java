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

package net.sf.ehcache.store.compound.impl;

import net.sf.ehcache.Element;
import net.sf.ehcache.store.compound.IdentityElementProxyFactory;

public class InMemoryFactory extends IdentityElementProxyFactory {

    @Override
    public Element decode(Object key, Element element) {
        return element;
    }

    @Override
    public Element encode(Object key, Element element) {
        return element;
    }

    @Override
    public void free(Element element) {
        // no-op
    }

    public void freeAll() {
        // no-op
    }
}
