# Render provides no native runtime for JVM languages -- its six are Node, Python, Ruby, Go,
# Rust and Elixir -- so a container is the supported way to deploy this, not a preference.
#
# Two stages so the JDK, the Gradle distribution and the whole dependency cache stay in the
# build stage. What ships is a JRE and one jar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# The build definition first, on its own layer. Dependencies are then resolved once and reused
# for as long as that definition is unchanged, so editing a source file does not refetch them.
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN ./gradlew --no-daemon --quiet dependencies

COPY src src

# bootJar rather than build, and that is the point: it compiles and packages without running
# tests. The suite starts a real PostgreSQL through Zonky, which would mean downloading and
# running a database server inside an image build to prove something the build machine already
# proved. Tests stay a local and CI gate.
RUN ./gradlew --no-daemon bootJar


FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Nothing here needs root, and a container that never had it cannot lose it.
RUN useradd --system --create-home --uid 10001 flashcards

# Exactly one jar matches, checked rather than assumed: from an empty build/libs, `bootJar`
# alone produces the boot jar and not the `-plain` one that `build` adds. A local `build` does
# leave that second jar behind, which is the other reason .dockerignore excludes build/ --
# without it a stale plain jar would enter the context and this COPY would fail on two matches.
COPY --from=build /build/build/libs/*.jar app.jar

USER flashcards

# Render's default. Documentation only -- the port actually bound comes from PORT at runtime,
# which application.properties reads.
EXPOSE 10000

# The JVM sizes the heap at a quarter of the container's memory by default, which on a small
# instance leaves Spring, Hibernate and Hikari sharing far less than the box actually has.
# Set on the command line rather than through JAVA_TOOL_OPTIONS, which the JVM announces on
# stderr at every start.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
