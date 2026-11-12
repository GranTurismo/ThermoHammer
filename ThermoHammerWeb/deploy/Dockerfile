# The site generator's runtime: Python plus the four libraries it needs. The code itself is mounted, not baked in,
# so a change to the site is a file copy and a restart. The loop looks at the boards every 2 minutes (two small API
# requests) so a run shared from an app gets its page soon after it is submitted; it only rebuilds when they changed.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir "pillow==13.0.0" "jinja2==3.1.6" "fonttools==4.68.0" "segno==1.6.6"
WORKDIR /app
USER 1000:1000
CMD ["python", "build.py", "--loop", "120"]
