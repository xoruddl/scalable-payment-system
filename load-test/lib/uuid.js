/**
 * UUID v4 생성.
 *
 * k6의 jslib은 실행할 때마다 네트워크에서 받아오는데, 부하 테스트 도구가 외부 의존성 때문에
 * 시작을 못 하면 곤란하다. 열 줄이면 되는 걸 굳이 밖에서 가져오지 않는다.
 */
export function uuid() {
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
		const r = (Math.random() * 16) | 0;
		return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
	});
}
