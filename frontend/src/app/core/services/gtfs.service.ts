import {HttpClient} from "@angular/common/http";
import {inject, Injectable} from "@angular/core";
import {Observable} from "rxjs";

@Injectable({providedIn: "root"})
export class GtfsService {
	private readonly httpClient = inject(HttpClient);

	listFeeds(): Observable<string[]> {
		return this.httpClient.get<string[]>("/api/gtfs/feeds");
	}

	feedSummary(feedName: string): Observable<GtfsFeedSummary> {
		return this.httpClient.get<GtfsFeedSummary>(
			`/api/gtfs/feeds/${encodeURIComponent(feedName)}/summary`,
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
