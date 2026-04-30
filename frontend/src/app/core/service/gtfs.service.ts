import {HttpClient} from "@angular/common/http";
import {inject, Injectable, signal} from "@angular/core";
import {defer, finalize, Observable} from "rxjs";

@Injectable({providedIn: "root"})
export class GtfsService {
	private readonly httpClient = inject(HttpClient);

	/** True until the first {@link listFeeds} call resolves — drives the global loading dialog. */
	private readonly _loading = signal<boolean>(true);
	readonly loading = this._loading.asReadonly();

	listFeeds(): Observable<string[]> {
		return defer(() => {
			this._loading.set(true);
			return this.httpClient.get<string[]>("/api/gtfs/feeds")
				.pipe(finalize(() => this._loading.set(false)));
		});
	}

	feedSummary(feedName: string): Observable<GtfsFeedSummary> {
		return this.httpClient.get<GtfsFeedSummary>(
			`/api/gtfs/feeds/${encodeURIComponent(feedName)}/summary`,
		);
	}

	feedBoundingBox(feedName: string): Observable<BoundingBox> {
		return this.httpClient.get<BoundingBox>(
			`/api/gtfs/feeds/${encodeURIComponent(feedName)}/bbox`,
		);
	}
}

export interface GtfsFeedSummary {
	readonly name: string;
	readonly agencies: readonly string[];
	readonly stops: number;
	readonly routes: number;
	readonly trips: number;
}

/** Geographic bounding box matching {@code com.dimsumdetours.gtfs.BoundingBox}. */
export interface BoundingBox {
	readonly south: number;
	readonly west: number;
	readonly north: number;
	readonly east: number;
}
