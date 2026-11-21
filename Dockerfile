# ---------- Spice Garden: Java backend + web page, deployed on Render ----------
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Compile the Java files (RestaurantApp.java = domain classes, Main.java = web server)
COPY *.java ./
RUN javac *.java

# Web page and images served by the Java server
COPY index.html ./
COPY public ./public

# Render sets PORT automatically; 8080 is used when it is absent
ENV PORT=8080
EXPOSE 8080

CMD ["java", "-Xmx256m", "Main"]
