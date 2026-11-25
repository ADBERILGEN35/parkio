"""#617: the one-way widening of the app healthcheck start period from 60 s to 180 s (the shared
x-app-healthcheck of docker/docker-compose.apps.yml), for the slack-biz Compose integration check
(validate-compose-integration.sh).

accepted_moves(base_services, head_services) -> {service: (base start_period, head start_period)}
without_start_period(services, moves) -> a copy of services without the moved services' start_period

A move is accepted for one of the ten app services only when its rendered healthcheck has
start_period 1m0s on the base and exactly 3m0s on the head. The check then compares such a service
without healthcheck.start_period, and nothing else is dropped. Every other healthcheck difference
(test, interval, timeout, retries, start_interval) still fails, as do any other start period, any
later change of this one (the base then renders 3m0s) and the same move on any other service.
"""
import copy

SERVICES = ("gateway-service", "auth-service", "user-service", "parking-service", "media-service",
            "gamification-service", "notification-service", "moderation-service",
            "ai-validation-service", "analytics-service")
BASE_START_PERIOD = "1m0s"
HEAD_START_PERIOD = "3m0s"


def accepted_moves(base_services, head_services):
    moves = {}
    for service in SERVICES:
        base = (base_services.get(service) or {}).get("healthcheck")
        head = (head_services.get(service) or {}).get("healthcheck")
        if isinstance(base, dict) and isinstance(head, dict) \
                and base.get("start_period") == BASE_START_PERIOD \
                and head.get("start_period") == HEAD_START_PERIOD:
            moves[service] = (BASE_START_PERIOD, HEAD_START_PERIOD)
    return moves


def without_start_period(services, moves):
    services = copy.deepcopy(services)
    for service in moves:
        services[service]["healthcheck"].pop("start_period", None)
    return services
