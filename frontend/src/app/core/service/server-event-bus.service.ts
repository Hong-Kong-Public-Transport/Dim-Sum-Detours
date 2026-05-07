import {DestroyRef, inject, Injectable} from "@angular/core";
import {Observable, Subject} from "rxjs";

import type {ClockSnapshot} from "../model/clock.model";
import type {MilestoneEvent} from "../model/milestone.model";
import type {VehicleEvent} from "../model/vehicle.model";
import type {CargoEvent} from "../model/cargo.model";
import type {OrderEvent} from "./restaurant.service";
import type {GameEvent} from "./game-event.service";

/**
 * Phase-19 Phase-G: single SSE connection that fans out into per-domain
 * RxJS subjects. Replaces the pre-Phase-G model where each domain service
 * opened its own {@link EventSource} (clock, orders, vehicles, milestones,
 * events) — five connections were comfortably under the browser's 6-per-
 * origin SSE cap, but adding a sixth (Phase-19+ presence channel, a debug
 * trace stream, etc.) would have hit the wall.
 *
 * <p>The wire envelope is the {@code com.dimsumdetours.api.ServerEvent}
 * sealed hierarchy: each frame is {@code {type: "CLOCK"|"VEHICLE"|"ORDER"
 * |"MILESTONE"|"GAME", payload: {...}}}. This service decodes the
 * discriminator and pushes each payload onto the matching subject; domain
 * services subscribe to the subject they care about and reapply their
 * existing state-mutation logic verbatim.
 *
 * <p>The bus does NOT buffer events. Every domain service is
 * {@code providedIn: "root"} and is constructed eagerly via {@link
 * AppComponent}'s DI graph, so by the time the first SSE frame arrives
 * every subject already has its subscriber. A late-subscribing component
 * (e.g. lazy-loaded route) reads the relevant signal off its domain
 * service rather than the raw subject — those signals already carry the
 * latest state.
 */
@Injectable({providedIn: "root"})
export class ServerEventBusService {
	private readonly destroyRef = inject(DestroyRef);

	private readonly _clock = new Subject<ClockSnapshot>();
	private readonly _vehicle = new Subject<VehicleEvent>();
	private readonly _order = new Subject<OrderEvent>();
	private readonly _milestone = new Subject<MilestoneEvent>();
	private readonly _game = new Subject<GameEvent>();
	private readonly _cargo = new Subject<CargoEvent>();

	readonly clock$: Observable<ClockSnapshot> = this._clock.asObservable();
	readonly vehicle$: Observable<VehicleEvent> = this._vehicle.asObservable();
	readonly order$: Observable<OrderEvent> = this._order.asObservable();
	readonly milestone$: Observable<MilestoneEvent> = this._milestone.asObservable();
	readonly game$: Observable<GameEvent> = this._game.asObservable();
	readonly cargo$: Observable<CargoEvent> = this._cargo.asObservable();

	private eventSource?: EventSource;

	constructor() {
		this.openStream();
		this.destroyRef.onDestroy(() => this.eventSource?.close());
	}

	private openStream(): void {
		try {
			this.eventSource = new EventSource("/api/game/stream");
			this.eventSource.onmessage = (message) => {
				let envelope: ServerEventEnvelope;
				try {
					envelope = JSON.parse(message.data) as ServerEventEnvelope;
				} catch (err) {
					console.error("server-event-bus frame failed to parse", err, message.data);
					return;
				}
				switch (envelope.type) {
					case "CLOCK":
						this._clock.next(envelope.payload as ClockSnapshot);
						break;
					case "VEHICLE":
						this._vehicle.next(envelope.payload as VehicleEvent);
						break;
					case "ORDER":
						this._order.next(envelope.payload as OrderEvent);
						break;
					case "MILESTONE":
						this._milestone.next(envelope.payload as MilestoneEvent);
						break;
					case "GAME":
						this._game.next(envelope.payload as GameEvent);
						break;
					case "CARGO":
						this._cargo.next(envelope.payload as CargoEvent);
						break;
					default: {
						const _exhaustive: never = envelope.type;
						console.warn("server-event-bus unknown envelope type", _exhaustive, message.data);
					}
				}
			};
			this.eventSource.onerror = (err) => {
				// EventSource auto-reconnects; we only log so a long-disconnect
				// shows up in dev consoles. Cold-boot recovery is owned by the
				// ClockService epoch-counter effect in {@link GameEventService}.
				console.warn("server-event-bus stream error (browser will auto-reconnect)", err);
			};
		} catch (err) {
			console.error("server-event-bus stream failed to open", err);
		}
	}
}

/** Wire shape of {@code com.dimsumdetours.api.ServerEvent}. */
interface ServerEventEnvelope {
	readonly type: "CLOCK" | "VEHICLE" | "ORDER" | "MILESTONE" | "GAME" | "CARGO";
	readonly payload: unknown;
}


