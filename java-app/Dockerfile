FROM openjdk:17-jdk-slim

WORKDIR /opt/app

RUN apt-get update && apt-get install -y \
    curl \
    maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

COPY config /opt/app/config

EXPOSE 8081

CMD ["java", "-jar", "target/webcam-service-1.2.3.jar"]