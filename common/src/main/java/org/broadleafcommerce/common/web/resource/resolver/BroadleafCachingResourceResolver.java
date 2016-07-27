/*
 * #%L
 * broadleaf-theme
 * %%
 * Copyright (C) 2009 - 2015 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.common.web.resource.resolver;

import org.broadleafcommerce.common.logging.LogCategory;
import org.broadleafcommerce.common.logging.RequestLoggingUtil;
import org.broadleafcommerce.common.site.domain.Theme;
import org.broadleafcommerce.common.web.BroadleafRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.servlet.resource.AbstractResourceResolver;
import org.springframework.web.servlet.resource.CachingResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import java.util.List;

import javax.servlet.http.HttpServletRequest;

/**
 * A ResourceResolver that handles using the theme as part of the cache key and adds in
 * support to disable with environment properties.
 *
 * We bypass {@link CachingResourceResolver} and instead borrow its code in order to be
 * able to inject the theme key that is needed by BLC since Spring's class could not be
 * leveraged otherwise.
 *
 *  {@code }
 * 
 * @author Brian Polster
 * @since Broadleaf 4.0
 */
@Component("blCacheResourceResolver")
public class BroadleafCachingResourceResolver extends AbstractResourceResolver implements Ordered {

    public static final String RESOLVED_RESOURCE_CACHE_KEY_PREFIX = "resolvedResource:";
    public static final String RESOLVED_URL_PATH_CACHE_KEY_PREFIX = "resolvedUrlPath:";
    public static final String RESOLVED_RESOURCE_CACHE_KEY_PREFIX_NULL = "resolvedResourceNull:";
    public static final String RESOLVED_URL_PATH_CACHE_KEY_PREFIX_NULL = "resolvedUrlPathNull:";
    private static final Object NULL_REFERENCE = new Object();
    private int order = BroadleafResourceResolverOrder.BLC_CACHE_RESOURCE_RESOLVER;

    private final Cache cache;

    @javax.annotation.Resource(name = "blSpringCacheManager")
    private CacheManager cacheManager;
    
    private static final String DEFAULT_CACHE_NAME = "blResourceCacheElements";

    @Value("${resource.caching.enabled:true}")
    protected boolean resourceCachingEnabled;

    @javax.annotation.Resource(name = "blRequestLoggingUtil")
    protected RequestLoggingUtil requestLoggingUtil;

    @Autowired
    public BroadleafCachingResourceResolver(@Qualifier("blSpringCacheManager") CacheManager cacheManager) {
        this(cacheManager.getCache(DEFAULT_CACHE_NAME));
    }

    // Allows for an implementor to override the default cache settings.
    public BroadleafCachingResourceResolver(Cache cache) {
        Assert.notNull(cache, "'cache' is required");
        this.cache = cache;
    }

    /**
     * Return the configured {@code Cache}.
     */
    public Cache getCache() {
        return this.cache;
    }

    public boolean shouldUseResourceCache() {
        return resourceCachingEnabled;
    }

    @Override
    protected Resource resolveResourceInternal(HttpServletRequest request, String requestPath,
            List<? extends Resource> locations, ResourceResolverChain chain) {
        if (shouldUseResourceCache()) {
            String key = computeKey(request, requestPath) + getThemePathFromBRC();
            requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                    "Resource caching is enabled checking for resource with key " + key);
            Resource resource = this.cache.get(key, Resource.class);

            if (resource != null && resource.exists()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Found match");
                }
                requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                        "Resource exists for key " + key);
                return resource;
            }

            requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                    "Resource not found or did not exist for key " + key);

            resource = chain.resolveResource(request, requestPath, locations);
            if (resource != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Putting resolved resource in cache");
                }
                requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                        "Resource resolved, adding to cache with key " + key);
                this.cache.put(key, resource);
            }

            if (logger.isDebugEnabled() || requestLoggingUtil.isRequestLoggingEnabled()) {
                if (resource == null) {
                    logger.debug("Cache resolver, returned a null resource " + requestPath);
                    requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                            "Cache resolver, returned a null resource " + requestPath);

                } else if (!resource.exists()) {
                    logger.debug("Cache resolver, returned a resource that doesn't exist "
                            + requestPath + " - " + resource);
                    requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                            "Cache resolver, returned a resource that doesn't exist " +
                                    requestPath + resource);
                }
            }
            return resource;
        } else {
            return chain.resolveResource(request, requestPath, locations);
        }
    }

    /**
     * Pulled from {@link CachingResourceResolver}
     *
     * @param request
     * @param requestPath
     * @return
     */
    protected String computeKey(HttpServletRequest request, String requestPath) {
        StringBuilder key = new StringBuilder(RESOLVED_RESOURCE_CACHE_KEY_PREFIX);
        key.append(requestPath);
        if (request != null) {
            String encoding = request.getHeader("Accept-Encoding");
            if (encoding != null && encoding.contains("gzip")) {
                key.append("+encoding=gzip");
            }
        }
        return key.toString();
    }

    @Override
    protected String resolveUrlPathInternal(String resourceUrlPath,
            List<? extends Resource> locations, ResourceResolverChain chain) {
        if (shouldUseResourceCache()) {
            String response = null;

            String notFoundKey = RESOLVED_URL_PATH_CACHE_KEY_PREFIX_NULL + resourceUrlPath + getThemePathFromBRC();
            Object nullResource = getCache().get(notFoundKey, Object.class);
            if (nullResource != null) {
                logNullReferenceUrlPatchMatch(resourceUrlPath);
                return null;
            }

            String foundKey = RESOLVED_URL_PATH_CACHE_KEY_PREFIX + resourceUrlPath + getThemePathFromBRC();

            String resolvedUrlPath = this.cache.get(foundKey, String.class);
            if (resolvedUrlPath != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Found match");
                }
                if (requestLoggingUtil.isRequestLoggingEnabled()) {
                    requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                            String.format("Resolve URL Path key for '%s' yields '%s'", foundKey, resolvedUrlPath));
                    response = resolvedUrlPath;
                }
            } else {
                resolvedUrlPath = chain.resolveUrlPath(resourceUrlPath, locations);
                if (resolvedUrlPath != null) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("Putting resolved resource URL path in cache");
                    }
                    if (requestLoggingUtil.isRequestLoggingEnabled()) {
                        requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                                String.format("Putting resolved resource URL path in cache '%s' with resolved path '%s'"
                                        , foundKey, resolvedUrlPath));
                    }

                    this.cache.put(foundKey, resolvedUrlPath);
                    response = resolvedUrlPath;
                }
            }

            if (response == null) {
                if (logger.isTraceEnabled()) {
                    logger.trace(String.format("Putting resolved null reference url " +
                            "path in cache for '%s'", resourceUrlPath));
                }
                if (requestLoggingUtil.isRequestLoggingEnabled()) {
                    requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                            String.format("Putting resolved null reference url " +
                                    "path in cache for '%s'", resourceUrlPath));
                }
                getCache().put(notFoundKey, NULL_REFERENCE);
            }
            return response;
        } else {
            return chain.resolveUrlPath(resourceUrlPath, locations);
        }
    }

    private void logNullReferenceUrlPatchMatch(String resourceUrlPath) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("Found null reference url path match for '%s'", resourceUrlPath));
        }

        if (requestLoggingUtil.isRequestLoggingEnabled()) {
            requestLoggingUtil.logDebug(LogCategory.BL_RESOURCE_RESOLVER, getClass(),
                String.format("Found null reference url path match for '%s'", resourceUrlPath));
        }

    }

    /**
     * Returns the theme path from the {@link org.broadleafcommerce.common.web.BroadleafRequestContext} or an empty
     * string if no theme was resolved
     *
     * @return
     */
    protected String getThemePathFromBRC() {
        String themePath = null;
        Theme theme = BroadleafRequestContext.getBroadleafRequestContext().getTheme();
        if (theme != null) {
            themePath = theme.getPath();
        }
        return themePath == null ? "" : "-" + themePath;
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

}
