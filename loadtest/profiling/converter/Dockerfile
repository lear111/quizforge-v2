# Standalone converter only; no profiler agent is attached to the app.
FROM eclipse-temurin:21-jre
ADD --checksum=sha256:ef58045432b56f0064db721b707bbabeee93551ab1b0a0befbbff22574b16eeb https://github.com/async-profiler/async-profiler/releases/download/v4.2/jfr-converter.jar /opt/jfr-converter.jar
ENTRYPOINT ["java", "-Xmx768m", "-jar", "/opt/jfr-converter.jar"]
