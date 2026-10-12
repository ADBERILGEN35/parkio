"""U13 (CL-F11): the one-way move of the six app services the hosted-beta deploy built on the host to the
digest pins of docker/docker-compose.app-release-pins.yml, for the slack-biz Compose integration check
(validate-compose-integration.sh).

accepted_moves(base_services, head_services, pin_file_text) -> {service: pin}

A move is accepted for one of the six services only when:
  - the base service builds (has `build`) and names no image, as the six did before the pins;
  - the pin file names the service once, as ghcr.io/adberilgen35/parkio/<service>@sha256:<64 hex>;
  - the head service's image is exactly that pin.
The check then compares such a service without its image. Every other image difference still fails,
including any later change of these pins: the base then names an image.
"""
import re

APP_PIN_FILE = "docker/docker-compose.app-release-pins.yml"
SERVICES = ("user-service", "gamification-service", "notification-service", "moderation-service",
            "ai-validation-service", "analytics-service")
ENTRY = re.compile(r"(?m)^  ([a-z0-9-]+):\n    image: (\S+)[ \t]*$")


def pin_file_images(text):
    """{service: image} of the pin file's entries; a service named more than once maps to None."""
    found = {}
    for service, image in ENTRY.findall(text or ""):
        found[service] = None if service in found else image
    return found


def accepted_moves(base_services, head_services, pin_file_text):
    named = pin_file_images(pin_file_text)
    moves = {}
    for service in SERVICES:
        base = base_services.get(service) or {}
        head = head_services.get(service) or {}
        pin = named.get(service)
        digest_pin = rf"ghcr\.io/adberilgen35/parkio/{re.escape(service)}@sha256:[0-9a-f]{{64}}"
        if "image" not in base and base.get("build") and pin and head.get("image") == pin \
                and re.fullmatch(digest_pin, pin):
            moves[service] = pin
    return moves
