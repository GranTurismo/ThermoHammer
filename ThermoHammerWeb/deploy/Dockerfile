# The site generator's runtime: Python plus the three libraries it needs. The code itself is mounted, not baked in,
# so a change to the site is a file copy and a restart.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir "pillow==13.0.0" "jinja2==3.1.6" "fonttools==4.68.0"
WORKDIR /app
USER 1000:1000
CMD ["python", "build.py", "--loop", "600"]
