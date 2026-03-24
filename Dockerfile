FROM eclipse-temurin:17-jdk-alpine
 
WORKDIR /app
 
 
COPY ./target/truckhire-0.1.0-SNAPSHOT.jar app.jar
 
EXPOSE 8505
 
CMD ["java" ,"-jar" ,"app.jar"]