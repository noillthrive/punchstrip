package mabibridge.mcp;

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.ServiceLoader;

import mabibridge.engine.BridgeEngine;

/** {@link CompareHook} 구현을 찾는다. 없으면 null(compare 계열 도구는 NOT_WIRED를 돌려준다). */
public final class CompareHooks {

	/** 반사로 찾는 기본 구현 클래스 이름. */
	public static final String DEFAULT_IMPL = "mabibridge.compare.CompareHookImpl";

	private CompareHooks() {}

	/**
	 * 1) {@link ServiceLoader}로 등록된 구현, 2) {@value #DEFAULT_IMPL} 클래스(생성자 {@code (BridgeEngine)} 우선,
	 * 없으면 인자 없음) 순서로 찾는다. 실패는 stderr에 적고 null.
	 */
	public static CompareHook discover(BridgeEngine engine) {
		try {
			Optional<CompareHook> s = ServiceLoader.load(CompareHook.class).findFirst();
			if (s.isPresent()) {
				return s.get();
			}
		} catch (Throwable e) {
			System.err.println("[mcp] CompareHook ServiceLoader 실패: " + e);
		}
		Class<?> c;
		try {
			c = Class.forName(DEFAULT_IMPL);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
		try {
			if (!CompareHook.class.isAssignableFrom(c)) {
				System.err.println("[mcp] " + DEFAULT_IMPL + "가 CompareHook을 구현하지 않음. 연결하지 않음");
				return null;
			}
			try {
				Constructor<?> k = c.getConstructor(BridgeEngine.class);
				return (CompareHook) k.newInstance(engine);
			} catch (NoSuchMethodException e) {
				return (CompareHook) c.getConstructor().newInstance();
			}
		} catch (Throwable e) {
			System.err.println("[mcp] " + DEFAULT_IMPL + "를 만들지 못함. 연결하지 않음: " + e);
			return null;
		}
	}
}
