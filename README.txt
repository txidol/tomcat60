General
=======

The sandbox is for experimental development of Tomcat components. The components
may provide new functionality or improve existing functionality.

Components may be targetted at one or more Tomcat versions.


Life-cycle
==========

The life-cycle of a sandbox component is:
1. Create new component in sandbox
2. Develop it
3. Do one of
   i)   Incorporate it in the standard Tomcat source trees
   ii)  Release it as a separate component
   iii) Archive it for future reference


SVN
===

Sandbox components should be developed in a sub-directory of the /tomcat/sandbox
directory. Developers may choose whether to add source directly under the
component's directory or to create the standard subversion /trunk/, /branches/
and /tags/ directories and add the source under the /trunk/ directory.

The svn commands to do this will look something like:
svn mkdir https://svn.apache.org/repos/asf/tomcat/sandbox/component
svn mkdir https://svn.apache.org/repos/asf/tomcat/sandbox/component/trunk
svn mkdir https://svn.apache.org/repos/asf/tomcat/sandbox/component/branches
svn mkdir https://svn.apache.org/repos/asf/tomcat/sandbox/component/tags

Alternatively, you can use the java/ directory. If possible:
1. create a new package ( or few ) for each module
2. if you have an external dependency for an optional component - create a new package.
3. Update the top level build.xml file with targets for your module - use <include> patterns instead
of exclude. 

The single tree may help increase visibility of the source code. We are in 2005 ( or more ), build tools
and IDEs have very good support for include/exclude patterns, we have tools to find dependencies between
packages, and we have a single community, so we don't need fragmented source trees and more, at least in 
sandbox.

Releases
========

Components may be released from the sandbox but only as alpha or beta. If
releases are made, then the standard subversion structure of /trunk/,
/branches/ and /tags/ must be used.

Stable releases may not be made from the sandbox.

All releases are subject to the same voting rules as any other Tomcat release.

If released as a separate component, and if it does not already exist,
a new top level directory (/tomcat/modules/) will need to be created and a
sub-directory for the new component should be created under the modules
directory. At this point, the standard svn /trunk/, /branches/ and /tags/ must
be used for each component.
