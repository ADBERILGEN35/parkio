"""N-17 (#378): one-way move of infra services from mutable tags to digest pins in
docker/docker-compose.infra-release-pins.yml, for the slack-biz Compose integration check
(validate-compose-integration.sh).

accepted_moves(base_services, head_services, pin_file_text) -> {service: pin}

A move is accepted for a service named in the infra pin file only when:
  - the pin file names the service once as <repo>@sha256:<64 hex> (merge keys such as
    <<: *infra-pin may sit between the service key and image);
  - the head service's image is exactly that pin;
  - the base service's image is mutable (no @sha256 digest).
The check then compares such a service without its image. Every other image difference still
fails, including any later change of these pins: the base then already names a digest.
Services listed only for pull_policy (minio / minio-setup) are not image moves.
"""
import re

INFRA_PIN_FILE = "docker/docker-compose.infra-release-pins.yml"
SERVICE = re.compile(r"(?m)^  ([a-z0-9-]+):\s*$")
IMAGE = re.compile(r"(?m)^    image: (\S+)\s*$")
DIGEST = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")


def pin_file_images(text):
    """{service: image} of the pin file's image entries; a service named more than once maps to None."""
    found = {}
    service = None
    for line in (text or "").splitlines():
        key = SERVICE.fullmatch(line)
        if key:
            service = key.group(1)
            continue
        image = IMAGE.fullmatch(line)
        if image and service:
            found[service] = None if service in found else image.group(1)
            service = None
    return found


def accepted_moves(base_services, head_services, pin_file_text):
    named = pin_file_images(pin_file_text)
    moves = {}
    for service, pin in named.items():
        if not pin or not DIGEST.fullmatch(pin):
            continue
        base = base_services.get(service) or {}
        head = head_services.get(service) or {}
        base_image = base.get("image") or ""
        if "@sha256:" in base_image:
            continue  # later pin retarget is not this one-way move
        if not base_image:
            continue
        if head.get("image") == pin:
            moves[service] = pin
    return moves
