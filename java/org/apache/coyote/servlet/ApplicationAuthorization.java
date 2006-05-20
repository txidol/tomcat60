/*
 */
package org.apache.coyote.servlet;

import java.security.Principal;

/** Subset of Realm. 
 * 
 * Authentication and web.xml-defined authorization are done in a filter ( valve ), 
 * this is needed for the programmatic callbacks.
 * 
 * 
 * 
 * @author Costin Manolache
 */
public class ApplicationAuthorization {
    
    public boolean hasRole(Principal userPrincipal, String realRole) {
        return false;
    }

    
}
