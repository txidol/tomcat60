This branch is created with the goal to improve JUnit tests in Tomcat 6.

It is maintained as a reintegrate-able branch with regular catch-up
merges from tc6.0.x/trunk.

Created: 2012-11-12 from r1408230
Last catch-up merge: none yet


TODO:

 * Backport support for running JUnit tests to the main /build.xml
   file from Tomcat 7. Add <target name="test">.                    [Not Started]

 * Drop useless test/build.xml                                      [Not Started]

 * Review existing tests, align with Tomcat 7, convert to JUnit 4.  [Not Started]

 * Update BUILDING.txt.                                             [Not Started]

 * The results at this point can be proposed to be merged back to
   tc6.0.x/trunk.

 * Maybe it will be possible to backport the tests that start a Tomcat
   server instance, using an idea from [1].                         [Not Started]

   [1]  http://tomcat.markmail.org/thread/ko7ip7obvyaftwe4

 * Configure Apache Gump to run the tests.                          [Not Started]


(Regarding BRANCH-README files - see Apache Subversion Community Guide
 http://subversion.apache.org/docs/community-guide/general.html#branch-policy
)
