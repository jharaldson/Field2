



spinCamera = (c, amount) => {	
	c.advanceState( x => {
		x = x.orbitLeft(amount)
		return x
	})
	
	_.redraw()
}

_r = () => {
	spinCamera(_.camera, 0.01)
	spinCamera(_.fboCameras[0], 0.01)
	spinCamera(_.fboCameras[1], -0.01)
	
}