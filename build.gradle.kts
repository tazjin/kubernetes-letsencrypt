import com.bmuschko.gradle.docker.DockerExtension
import com.bmuschko.gradle.docker.DockerJavaApplication

buildscript {

    repositories {
        gradleScriptKotlin()
    }

    dependencies {
        classpath(kotlinModule("gradle-plugin"))
        classpath("com.bmuschko:gradle-docker-plugin:3.0.8")
    }
}

apply {
    plugin("kotlin")
    plugin("application")
    plugin("com.bmuschko.docker-java-application")
}

version = "1.7-SNAPSHOT"
group = "in.tazj.k8s"

configure<ApplicationPluginConvention> {
    mainClassName = "in.tazj.k8s.letsencrypt.MainKt"
}

configure<DockerExtension> {
    with(getProperty("javaApplication") as DockerJavaApplication) {
        baseImage = "java:8"
        tag = "tazjin/letsencrypt-controller:${version}"
    }
}

dependencies {
    compile(kotlinModule("stdlib"))
}

repositories {
    gradleScriptKotlin()
    mavenCentral()
    jcenter()
}

dependencies {
    compile(kotlinModule("stdlib"))
    compile("io.fabric8", "kubernetes-client", "2.2.13")
    compile("com.google.code.gson", "gson", "2.8.0")
    compile("com.amazonaws", "aws-java-sdk-route53", "1.11.119") {
        // Prevent AWS SDK from dragging in unwanted (unstructured) logging
        exclude(group = "commons-logging")
    }
    compile("org.shredzone.acme4j", "acme4j-client", "0.10")
    compile("org.shredzone.acme4j", "acme4j-utils", "0.10")
    compile("dnsjava", "dnsjava", "2.1.8")
    compile("com.google.cloud", "google-cloud-dns", "0.8.0")
    compile("org.funktionale", "funktionale-option", "1.0.1")

    // Structured logging
    compile("org.slf4j", "slf4j-api", "1.7.25")
    compile("ch.qos.logback", "logback-classic", "1.2.2")
    compile("net.logstash.logback", "logstash-logback-encoder", "4.9")
    compile("org.slf4j", "jcl-over-slf4j", "1.7.25")

    // Test dependencies
    testCompile("junit", "junit", "4.12")
    testCompile("org.mockito", "mockito-core", "2.8.9")
    testCompile("com.nhaarman", "mockito-kotlin", "1.4.0")
}