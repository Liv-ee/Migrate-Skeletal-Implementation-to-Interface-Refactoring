package p;

interface I {
	void m();
}

public abstract class A implements I {

	@Override
	public void m() {
		this.getClass();
	}
}