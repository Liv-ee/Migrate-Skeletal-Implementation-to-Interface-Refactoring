package p;

interface I {
	void m();
}

interface H extends I {	
}

interface J {
	void m();
}

interface K extends J {
}

abstract class B implements H {
}

abstract class A extends B implements K {
	public void m() {
	}
}

abstract class C extends A {	
}

//What are the type constraints for this? Should it pass? Can we fix it?
