package org.weceem.filter

import javax.servlet.*
import org.springframework.web.context.support.WebApplicationContextUtils
import org.weceem.util.MimeUtils
import org.weceem.security.WeceemSecurityPolicy

/**
 * Filter for files uploaded with CK Editor, to apply our security policy / status / caching rules
 */
class UploadedFileFilter implements Filter {
    
    def wcmContentFingerprintService
    def wcmContentRepositoryService
    def wcmSecurityService
    def cacheHeadersService
    
    void init(FilterConfig config) throws ServletException {
        def applicationContext = WebApplicationContextUtils.getWebApplicationContext(config.servletContext)
        wcmContentRepositoryService = applicationContext.wcmContentRepositoryService
        wcmSecurityService = applicationContext.wcmSecurityService
        cacheHeadersService = applicationContext.cacheHeadersService
        wcmContentFingerprintService = applicationContext.wcmContentFingerprintService
    }

    void destroy() {
    }

    void doFilter(ServletRequest request, ServletResponse response,
        FilterChain chain) throws IOException, ServletException {

        def info = wcmContentRepositoryService.resolveSpaceAndURIOfUploadedFile(request.requestURI - request.contextPath)
        def space = info.space
        def uri = info.uri
 
        def canView = wcmSecurityService.hasPermissions(space, uri, [WeceemSecurityPolicy.PERMISSION_VIEW])
        if (!canView) {
            canView = wcmSecurityService.hasPermissions(space, uri, [WeceemSecurityPolicy.PERMISSION_EDIT])
        }
        
        // @todo also implement Draft status checks here so ppl cannot view drafts even if they have perms (e.g. guests)
        if (!canView) {
            response.sendError(503, "You do not have permission to view this resource")
            return
        }

        def contentInfo = wcmContentRepositoryService.findContentForPath(uri, space)
        def content
        if (contentInfo?.content) {
            content = contentInfo.content
        }
        
        def f = wcmContentRepositoryService.getUploadPath(space, uri)
        if (!f.exists()) {
            response.sendError(404)
            return
        }
        
        def ctx = [
            cacheHeadersService:cacheHeadersService, 
            wcmContentFingerprintService:wcmContentFingerprintService, 
            wcmContentRepositoryService:wcmContentRepositoryService, 
            request:request, 
            response:response,
        ]
        cacheHeadersService.withCacheHeaders(ctx) { 
            etag {
                tagValue = wcmContentFingerprintService.getFingerprintFor(content)
            }
            
            lastModified {
                content ? wcmContentRepositoryService.getLastModifiedDateFor(content) : null
            }
            
            generate {
                def cacheMaxAge = content?.validFor ?: 1
                
                def publiclyCacheable = true
                
                cacheHeadersService.cache(response, [validFor: cacheMaxAge, shared:publiclyCacheable])

                def mt = MimeUtils.getDefaultMimeType(f.name)
                response.setContentType(mt)    
                // @todo is this fast enough?    
                response.outputStream << f.newInputStream()
            }
        }
    }
}