# One image for all three roles; SPRING_PROFILES_ACTIVE picks the role.
# Build the jar first:  ./mvnw -DskipTests package
# glibc-based JRE on purpose: docker-java's unix-socket transport uses JNA, which is unreliable on musl/Alpine.
FROM eclipse-temurin:17-jre

RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY target/job-scheduler.jar app.jar

USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
