# ---------- Spice Garden: Java backend + Customer & Admin Portals ----------
FROM eclipse-temurin:21-jdk

WORKDIR /app

# JDBC drivers (MySQL Connector/J + persistent H2 in MySQL mode)
COPY lib ./lib

# Compile the Java backend
COPY *.java ./
RUN mkdir -p out && javac -cp "lib/*" -d out *.java

# Web pages and assets served by the Java server
COPY index.html admin.html ./
COPY public ./public

# Render sets PORT automatically; 8080 is used when it is absent
ENV PORT=8080
EXPOSE 8080

CMD ["java", "-Xmx256m", "-cp", "out:lib/*", "Main"]
