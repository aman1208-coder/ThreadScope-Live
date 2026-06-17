FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Copy Maven wrapper and project files to leverage layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src ./src

RUN chmod +x mvnw && ./mvnw -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar from the builder stage
ARG JAR_FILE=target/threadscope-agent-2.0.0.jar
COPY --from=build /workspace/${JAR_FILE} /app/app.jar

# Default PORT environment variable (can be overridden by hosting provider)
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
