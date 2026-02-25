FROM --platform=linux/amd64 ubuntu:14.04

# Set JAVA_HOME early so ca-certificates-java postinst can find java during install
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH

# Java 8 (openjdk-r PPA), Python 2.7, astyle (MCP formatting), patch (used by fml install.py), unzip
# ln -s python2.7 → /usr/bin/python because update-alternatives has no registered python on 14.04
RUN apt-get update && apt-get install -y software-properties-common && \
    add-apt-repository ppa:openjdk-r/ppa && \
    apt-get update && \
    apt-get install -y openjdk-8-jdk python2.7 astyle unzip patch && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/bin/python2.7 /usr/bin/python

# MCP refuses to run as root — create dev user
RUN useradd -m -s /bin/bash dev
ENV HOME=/home/dev

# All downloads baked in — no internet needed during build
COPY --chown=dev:dev download/ /opt/ovm/download/

# Install MCP + Forge as dev user (decompiles Minecraft, applies Forge patches — ~15-30 min, cached layer)
# Layout: MCP root at /opt/forge, Forge src at /opt/forge_src/forge
# Pre-place all jars fml/install.py would download:
#   jars/bin/: lwjgl.jar, lwjgl_util.jar, jinput.jar, minecraft.jar, minecraft_server.jar
#   jars/bin/natives/: *_natives.jar (empty stubs — compilation only, no runtime needed)
#   lib/: argo, guava, asm, bcprov + source stubs (download_deps checks existence before fetching)
# mcp.cfg references astyle-osx binary; replace with system astyle
RUN mkdir -p /opt/forge /opt/forge_src && chown -R dev:dev /opt/forge /opt/forge_src
USER dev
RUN unzip /opt/ovm/download/mcp726a.zip -d /opt/forge && \
    unzip /opt/ovm/download/forge-1.4.7-6.6.2.534-src.zip -d /opt/forge_src && \
    mkdir -p /opt/forge/jars/bin/natives /opt/forge/lib && \
    cp /opt/ovm/download/client.jar             /opt/forge/jars/bin/minecraft.jar && \
    cp /opt/ovm/download/server.jar             /opt/forge/jars/minecraft_server.jar && \
    cp /opt/ovm/download/lwjgl.jar              /opt/forge/jars/bin/lwjgl.jar && \
    cp /opt/ovm/download/lwjgl_util.jar         /opt/forge/jars/bin/lwjgl_util.jar && \
    cp /opt/ovm/download/jinput.jar             /opt/forge/jars/bin/jinput.jar && \
    cp /opt/ovm/download/linux_natives.jar      /opt/forge/jars/bin/natives/linux_natives.jar && \
    cp /opt/ovm/download/windows_natives.jar    /opt/forge/jars/bin/natives/windows_natives.jar && \
    cp /opt/ovm/download/macosx_natives.jar     /opt/forge/jars/bin/natives/macosx_natives.jar && \
    cp /opt/ovm/download/argo-2.25.jar          /opt/forge/lib/argo-2.25.jar && \
    cp /opt/ovm/download/guava-12.0.1.jar       /opt/forge/lib/guava-12.0.1.jar && \
    cp /opt/ovm/download/guava-12.0.1-sources.jar /opt/forge/lib/guava-12.0.1-sources.jar && \
    cp /opt/ovm/download/asm-all-4.0.jar        /opt/forge/lib/asm-all-4.0.jar && \
    cp /opt/ovm/download/asm-all-4.0-source.jar /opt/forge/lib/asm-all-4.0-source.jar && \
    cp /opt/ovm/download/bcprov-jdk15on-147.jar /opt/forge/lib/bcprov-jdk15on-147.jar && \
    sed -i 's|runtime/bin/astyle-osx|astyle|g' /opt/forge/conf/mcp.cfg && \
    sed -i '/max-instatement-indent/d' /opt/forge/conf/astyle.cfg && \
    sed -i 's/decompile(None, False, False, True, True, False, True,/decompile(None, False, False, True, True, True, True,/' /opt/forge_src/forge/fml/fml.py && \
    cd /opt/forge_src/forge && python install.py --mcp-dir /opt/forge

# Additional lib JARs needed for mod compilation classpath
RUN cp /opt/ovm/download/commons-compress-1.4.1.jar \
       /opt/ovm/download/org.eclipse.jgit-2.2.0.201212191850-r.jar \
       /opt/forge/lib/

# Fix 86 known decompile/patch-rejection errors in Forge 1.4.7 + MCP 7.26 source.
# ~210 patches rejected due to decompiler variance; these stubs satisfy the compiler.
COPY --chown=dev:dev scripts/fix_forge_sources.py /tmp/fix_forge_sources.py
RUN python /tmp/fix_forge_sources.py

# Compile full Minecraft+Forge+FML source into bin/minecraft.
# Uses direct javac (not recompile.py) so we control encoding and error handling.
# All 1669 source files compiled in one pass against obfuscated minecraft.jar + lib/*.
RUN find /opt/forge/src/minecraft -name '*.java' > /tmp/forge_sources.txt && \
    mkdir -p /opt/forge/bin/minecraft && \
    javac -source 1.6 -target 1.6 -encoding UTF-8 \
      -cp "/opt/forge/lib/*:/opt/forge/jars/bin/minecraft.jar:/opt/forge/jars/bin/lwjgl.jar:/opt/forge/jars/bin/lwjgl_util.jar:/opt/forge_src/forge/fml/bin" \
      -d /opt/forge/bin/minecraft \
      @/tmp/forge_sources.txt 2>&1 | tee /tmp/forge_compile.log | grep 'error:' | head -20 ; \
    echo "Errors: $(grep -c 'error:' /tmp/forge_compile.log || echo 0)"

WORKDIR /opt/forge
CMD ["/bin/bash"]
