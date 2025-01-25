from tkinter import *
from tkinter.ttk import *
from time import time

root = Tk()
root.title("")

def timeFunc():
	current_time = time()
	
	time_str = current_time%1000
	time_str = format(time_str,'.3f')
	time_str = time_str.zfill(7)
	label.config(text = time_str)
	label.after(10,timeFunc)
	
label = Label(root, font = ("Roboto-Regular.ttf", 40), background = "white", foreground = "black")
label.pack(anchor = 'center', side = "left")
timeFunc()

root.geometry("%dx%d+%d+%d" % (300, 50, 10, 200)) # w, h, x, y
root.mainloop()
	
