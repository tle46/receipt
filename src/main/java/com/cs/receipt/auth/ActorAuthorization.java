package com.cs.receipt.auth;

import com.cs.receipt.controller.ReceiptController;
import com.cs.receipt.controller.UserController;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Map;

/** Authorize resolved MVC handlers and decoded IDs, including encoded request paths. */
@Configuration
public class ActorAuthorization implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (!(handler instanceof HandlerMethod method)) return true;
                boolean receipt = method.getBeanType() == ReceiptController.class;
                boolean user = method.getBeanType() == UserController.class;
                if (!receipt && !user) return true;
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (!(authentication instanceof JwtAuthenticationToken)) { response.sendError(401); return false; }
                String actor = authentication.getName();
                if (receipt) {
                    String[] ids = request.getParameterValues("userId");
                    if (ids != null) for (String id : ids) {
                        if (!actor.equals(id)) { response.sendError(403); return false; }
                    }
                }
                if (user) {
                    var variables = (Map<?,?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                    if (variables == null || !actor.equals(variables.get("userId"))) { response.sendError(403); return false; }
                }
                return true;
            }
        });
    }
}
