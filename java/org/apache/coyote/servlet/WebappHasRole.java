/*
 */
package org.apache.coyote.servlet;

import java.security.Principal;

/** 
 * Callback to check roles. By default, matching username and role is used,
 * should be overriden.
 * 
 * An authentication filter should use group if available or user matches.
 * 
 * @author Costin Manolache
 */
public class WebappHasRole {
    
    public boolean hasRole(Principal userPrincipal, String realRole) {
        if (realRole != null && realRole.equals(userPrincipal.getName())) {
            return true;
        }
        return false;
    }

    
}
