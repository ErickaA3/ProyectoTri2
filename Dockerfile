# ─── Etapa 1: Build con Maven ───────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copiar pom.xml primero para aprovechar el cache de dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar el código fuente y compilar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ─── Etapa 2: Runtime con Tomcat 10 ─────────────────────────────────────────
FROM tomcat:10.1-jdk21-temurin

# Limpiar apps por defecto de Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Copiar el WAR generado
COPY --from=build /app/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war

# Puerto que expone Tomcat
EXPOSE 8080

# Arrancar Tomcat
CMD ["catalina.sh", "run"]
