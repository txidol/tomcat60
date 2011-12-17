To release do the following:
1 - copy mvn.properties.default to mvn.propertie and adjust it.
2 - ant -f mvn-pub.xml deploy-release
    that step creates a staging in https://repository.apache.org/index.html#stagingRepositories
3 - test it and do the vote process
4 - in https://repository.apache.org/index.html#stagingRepositories close it and then promote it.
