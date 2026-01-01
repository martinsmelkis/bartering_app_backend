# 1. Start with the official PostGIS image.
FROM postgis/postgis:18-3.6

ENV PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin"

# 2. Install all build dependencies needed for the extensions.
# We run as the root user to install packages.
USER root
RUN apt-get update  \
    && apt-get install -y \
    build-essential \
    postgresql-plpython3-18 \
    postgresql-server-dev-18 \
    git \
    libpq-dev \
    libclang-dev \
    pkg-config \
    libcurl4-openssl-dev \
    ca-certificates \
    python3-pip \
    curl \
    flex \
    libkrb5-dev \
    && rm -rf /var/lib/apt/lists/*

# Install uv using the official install script (pre-built binary, no compilation needed)
RUN curl -LsSf https://astral.sh/uv/install.sh | sh

RUN curl https://sh.rustup.rs -sSf | sh -s -- -y

ENV PATH="${PATH}:/root/.cargo/bin:/usr/lib/postgresql/18/bin"

RUN cargo install just

# 4. Set a single working directory for all build operations.
WORKDIR /tmp

# 5. Download, compile, install, and clean up all three extensions in a single RUN layer.
# This is more efficient for Docker's layering system.
RUN \
    # --- Build pg_tle (a dependency for pgai) ---
    git clone --branch v1.5.2 https://github.com/aws/pg_tle.git \
    && cd pg_tle \
    && make \
    && make install \
    # Cleanup: Exit the directory and remove the source code
    && cd .. \
    && rm -rf pg_tle \
    \
    # --- Build pgvector ---
    && git clone --branch v0.8.1 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make \
    && make install \
    # Cleanup
    && cd .. \
    && rm -rf pgvector \
    \
    # --- Build and install pgai ---
    && git clone https://github.com/martinsmelkis/pgai.git \
    && cd pgai/projects/extension \
    && rm requirements-lock.txt \
    # 2. Use 'uv' to generate a new lock file from the main 'pyproject.toml'.
    #    This will select versions of all dependencies compatible with Python 3.13.
    && uv pip compile pyproject.toml --output-file requirements-lock.txt \
    && just build-install \
    && echo "--- Checking pgai build output ---" \
    && ls -lR sql/output/ \
    # Step 2: Explicitly determine the correct installation directories
    && export EXT_INSTALL_DIR=$(pg_config --sharedir)/extension \
    && export LIB_INSTALL_DIR=$(pg_config --pkglibdir) \
    # Step 3: Manually copy the files to the correct directories
    && cp sql/output/*.control "$EXT_INSTALL_DIR" \
    && cp sql/output/ai--*.sql "$EXT_INSTALL_DIR" \
    # Cleanup
    && cd ../.. \
    && rm -rf pgai

# 6. Copy the database initialization script (do this as root before cleanup)
COPY init-db.sh /docker-entrypoint-initdb.d/
RUN chmod +x /docker-entrypoint-initdb.d/init-db.sh

# 7. Clean up build-time dependencies to keep the final image size smaller.
# We leave 'curl' and 'ca-certificates' as pgai may need them at runtime to call Ollama.
RUN apt-get purge -y --ignore-missing build-essential pkg-config postgresql-server-dev-18 git libclang-dev flex libkrb5-dev && apt-get autoremove -y

# 8. Switch back to the default postgres user for security best practices.
USER postgres
