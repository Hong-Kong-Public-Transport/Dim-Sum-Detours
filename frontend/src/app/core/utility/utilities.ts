export function getCookie(name: string, defaultValue = "") {
	const splitCookies = document.cookie.split("; ").filter(cookie => cookie.startsWith(name + "="));
	if (splitCookies.length > 0 && splitCookies[0].includes("=")) {
		return decodeURIComponent(splitCookies[0].split("=")[1]);
	} else {
		return defaultValue;
	}
}

export function setCookie(name: string, value: string) {
	document.cookie = `${name}=${value}; expires=${new Date(2999, 11, 31).toUTCString()}; path=/`;
}
