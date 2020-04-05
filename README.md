# lecousin.net - Java network framework - core

This library provides network functionalities with multi-threading.

It depends on the [net.lecousin.core]("https://github.com/lecousin/java-framework-core" "java-framework-core") library
for asynchronous operations and to launch tasks on network events.

Main functionalities:
 * The NetworkManager is based on the Java NIO framework to listen to network events and launch tasks to handle them
 * The classes TCPClient and UDPClient allow to connect to a server and exchange messages asynchronously
 * The classes TCPServer and UDPServer implement a server to process client requests asynchronously
 * A TCPServer is based on a ServerProtocol, which provides the specific implementation of a protocol
 * The class SSLLayer allows to handle SSL messages exchange, and is used by the SSLClient and SSLServerProtocol

## Build status

### Current version - branch master

[![Maven Central](https://img.shields.io/maven-central/v/net.lecousin.framework.network/core.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22net.lecousin.framework.network%22%20AND%20a%3A%22core%22)
[![Javadoc](https://img.shields.io/badge/javadoc-0.7.6-brightgreen.svg)](https://www.javadoc.io/doc/net.lecousin.framework.network/core/0.7.6)

![build status](https://travis-ci.org/lecousin/java-framework-network-core.svg?branch=master "Build Status")
![build status](https://ci.appveyor.com/api/projects/status/github/lecousin/java-framework-network-core?branch=master&svg=true "Build Status")
[![Codecov](https://codecov.io/gh/lecousin/java-framework-network-core/graph/badge.svg)](https://codecov.io/gh/lecousin/java-framework-network-core/branch/master)

[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=master&metric=alert_status)](https://sonarcloud.io/dashboard?branch=master&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=master&metric=bugs)](https://sonarcloud.io/dashboard?branch=master&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=master&metric=vulnerabilities)](https://sonarcloud.io/dashboard?branch=master&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=master&metric=code_smells)](https://sonarcloud.io/dashboard?branch=master&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=master&metric=coverage)](https://sonarcloud.io/dashboard?branch=master&id=lecousin_java-framework-network-core)

### Development branch

![build status](https://travis-ci.org/lecousin/java-framework-network-core.svg?branch=dev "Build Status")
![build status](https://ci.appveyor.com/api/projects/status/github/lecousin/java-framework-network-core?branch=dev&svg=true "Build Status")
[![Codecov](https://codecov.io/gh/lecousin/java-framework-network-core/branch/dev/graph/badge.svg)](https://codecov.io/gh/lecousin/java-framework-network-core/branch/dev)

[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=dev&metric=alert_status)](https://sonarcloud.io/dashboard?branch=dev&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=dev&metric=bugs)](https://sonarcloud.io/dashboard?branch=dev&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=dev&metric=vulnerabilities)](https://sonarcloud.io/dashboard?branch=dev&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=dev&metric=code_smells)](https://sonarcloud.io/dashboard?branch=dev&id=lecousin_java-framework-network-core)
[![Sonar](https://sonarcloud.io/api/project_badges/measure?project=lecousin_java-framework-network-core&branch=dev&metric=coverage)](https://sonarcloud.io/dashboard?branch=dev&id=lecousin_java-framework-network-core)
