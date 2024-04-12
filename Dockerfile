FROM amazoncorretto:20-alpine
COPY /build/libs/the-mind-all.jar /app/the-mind.jar
ENTRYPOINT ["java", "-jar", "/app/the-mind.jar"]
