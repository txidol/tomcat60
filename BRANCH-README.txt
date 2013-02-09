This branch is created with the goal to improve JUnit tests in Tomcat 6.

It is maintained as a reintegrate-able branch with regular catch-up
merges from tc6.0.x/trunk.

Created: 2012-11-12 from r1408230
Last catch-up merge: 2013-02-04 (r1442376), merged up to r1442373

Revisions in 6.0.x/trunk that are merges from this branch:
r1417826,1444292


TODO:

 * Backport support for running JUnit tests to the main /build.xml
   file from Tomcat 7. Add <target name="test">.                    [Done]

   Notes:
     - The < if="${execute.test.bio}"> construct requires Ant >= 1.8.0
       http://ant.apache.org/manual/properties.html#if+unless

     - Separate "test-bio", "test-nio", "test-apr" targets are there,
       but they do not make much sense as the tests do not start Tomcat.

     - "test.jvmarg.egd=-Djava.security.egd=file:/dev/./urandom"
       property was not ported, as it is not needed for Tomcat 6.

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
