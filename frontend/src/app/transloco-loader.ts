import {HttpClient} from "@angular/common/http";
import {inject, Injectable} from "@angular/core";
import {Translation, TranslocoLoader} from "@jsverse/transloco";
import {Observable} from "rxjs";

@Injectable({providedIn: "root"})
export class TranslocoHttpLoader implements TranslocoLoader {
	private readonly httpClient = inject(HttpClient);

	getTranslation(language: string): Observable<Translation> {
		return this.httpClient.get<Translation>(`/assets/i18n/${language}.json`);
	}
}
