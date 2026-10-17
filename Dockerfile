FROM eclipse-temurin:21-jdk AS compilacion
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 digitalfix \
    && useradd --uid 10001 --gid digitalfix --no-create-home digitalfix \
    && mkdir /data && chown digitalfix:digitalfix /data && chmod 700 /data
WORKDIR /app
COPY --from=compilacion /app/target/*.jar app.jar
USER digitalfix
ENTRYPOINT ["java", "-jar", "app.jar"]
