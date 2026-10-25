# The routing service as a Cloudflare Container (or any Docker host).
# Built from the repository root: `wrangler deploy` does this itself through image_build_context in wrangler.jsonc;
# by hand: docker build -f server/Dockerfile -t fold-server . && docker run -p 8080:8080 fold-server
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server
# the Android app and the packer are not part of this image; an empty module keeps settings.gradle.kts valid
RUN mkdir -p app packer && echo "" > app/build.gradle.kts && echo "" > packer/build.gradle.kts
RUN chmod +x gradlew && ./gradlew --no-daemon -q :server:fatJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/libs/fold-server.jar ./fold-server.jar
ENV PORT=8080 PACK_DIR=/data/packs CATALOG_URL=https://hondoroadbook.com/packs/catalog.json
VOLUME ["/data"]
EXPOSE 8080
# the graphs are memory-mapped outside the heap; the heap holds search state, about 100 MB per big state
CMD ["java", "-Xmx1500m", "-XX:+UseSerialGC", "-jar", "fold-server.jar"]
