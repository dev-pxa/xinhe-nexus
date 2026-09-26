FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /app
COPY .mvn/settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY xinhe-common/pom.xml xinhe-common/
COPY xinhe-management/pom.xml xinhe-management/
COPY xinhe-app/pom.xml xinhe-app/
RUN mvn -B -s /root/.m2/settings.xml dependency:go-offline -pl xinhe-management -am
COPY . .
RUN mvn -B -s /root/.m2/settings.xml package -pl xinhe-management -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/xinhe-management/target/*.jar app.jar
EXPOSE 18081
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]

